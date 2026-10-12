import com.nerv.magi.core.MagiLogic;
import com.nerv.magi.core.MagiModels;
import com.nerv.magi.core.Prefs;
import com.nerv.magi.jev.MagiClient;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * JVM 端到端测试。
 *
 * <p><b>这是本工程最强的一道验证</b>：它跑的不是另写一遍的测试代码，而是
 * <b>原封不动、将要打进 APK 的那几个类</b>——{@code JeVQuestions}、{@code HttpJson}、
 * {@code JudgeClient}、{@code ReplyClient}、{@code MagiLogic}、{@code MagiClient}。
 * 只把 {@code Prefs} 唯一依赖的 {@code android.content.Context/SharedPreferences}
 * 换成两个内存替身，其余全部是真的。
 *
 * <p>因此它验证的是真实链路：题目 JSON 的形状、Authorization 头的拼法、
 * 429/4xx 的退避与不重试、{@code answers} 的解析、概率分布的读取、
 * 一票否决的判定顺序，以及四路并行编排。
 *
 * <p>{@code tools/smoke_test.py} 是另一条独立实现（Python），
 * 两者请求体应逐字一致——都跑通，才说明协议理解没跑偏。
 *
 * 用法:
 * <pre>
 *   java -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 \
 *        -cp "out:org.json.jar" MagiHarness "詢問" melchior人格…
 * </pre>
 */
public final class MagiHarness {

    public static void main(String[] args) throws Exception {
        String question = args.length > 0 ? args[0] : "我們應該啟動人類補完計劃嗎？";
        String key = args.length > 1 ? args[1] : readKey();

        Prefs prefs = new Prefs(new Context(), Prefs.PREFS_MAIN);
        // 走参考项目里 OpenRouter 那一档预设（与出廠設定一致）
        prefs.applyProviderDefaults(Prefs.PROVIDER_OPENROUTER);
        prefs.setJudgeKey(key);

        System.out.println("========================================================");
        System.out.println(" MAGI-EVA · JVM 端到端测试（跑的是将打进 APK 的真实代码）");
        System.out.println("========================================================");
        System.out.println(" endpoint : " + prefs.judgeEndpoint());
        System.out.println(" model    : " + prefs.judgeModel());
        System.out.println(" key len  : " + prefs.judgeKey().length());
        System.out.println(" question : " + question);
        System.out.println();

        MagiClient client = new MagiClient(prefs);

        long t0 = System.currentTimeMillis();
        MagiModels.Session s = client.conduct(question, new MagiClient.Progress() {
            public void onGate(MagiModels.Session live, MagiModels.Gate g) {
                if (g.failed()) {
                    System.out.println("[GATE] 失敗: " + g.call.error);
                } else {
                    System.out.printf(Locale.US,
                            "[GATE] is_yes_no=%.2f  is_decision=%.2f  %dms%n",
                            g.yesNo, g.decision, g.call.latencyMs);
                }
            }

            public void onUnit(MagiModels.Session live, MagiModels.UnitReading u) {
                if (!u.ok()) {
                    System.out.println("[" + u.label + "] 失敗: " + u.call.error);
                    return;
                }
                System.out.printf(Locale.US,
                        "[%s] verdict=%s conf=%.2f certainty=%.2f (%dms, %d tok, $%.6f)%n",
                        u.label, u.verdict == null ? "?" : u.verdict.choice,
                        u.verdict == null ? 0 : u.verdict.confidence,
                        u.certainty == null ? 0 : u.certainty.score,
                        u.call.latencyMs, u.call.usage.totalTokens(), u.call.usage.cost);
                if (u.verdict != null && !u.verdict.probabilities.isEmpty()) {
                    System.out.println("        probs " + u.verdict.probabilities);
                }
            }

            public void onDone(MagiModels.Session done) {
            }
        });

        System.out.println();
        System.out.println("---- 合議 ----");
        StringBuilder votes = new StringBuilder();
        for (MagiModels.UnitReading u : s.units) {
            if (votes.length() > 0) votes.append(" | ");
            votes.append(u.status.slug());
        }
        System.out.println(" 三賢人: " + votes);
        System.out.println(" 閘門  : isYesNo=" + s.gate.isYesNo);
        System.out.println(" 結論  : " + s.overall.label + "  (" + s.overall.slug().toUpperCase() + ")");
        System.out.println(" 釋義  : " + MagiLogic.explain(s.overall));
        System.out.printf(Locale.US, " 總計  : %dms  $%.6f  %d tokens%n",
                s.elapsedMs(), s.totalCost(), s.totalUsage().totalTokens());
        if (s.error != null) System.out.println(" 錯誤  : " + s.error);
        System.out.println(" 牆鐘  : " + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("========================================================");

        // 断言：结论必须与三贤人投票自洽（除非闸门失败）
        MagiModels.Status expect = MagiLogic.vote(s);
        if (s.error == null && expect != s.overall) {
            System.out.println("✕ 自洽性失敗：vote()=" + expect + " 但 overall=" + s.overall);
            System.exit(2);
        }
        System.out.println("✓ 结论与一票否决制自洽");
    }

    /**
     * 密钥解析顺序（都不涉及任何硬编码的个人路径）：
     * <ol>
     *   <li>命令行第 2 个参数</li>
     *   <li>环境变量 {@code JEV_KEY}</li>
     *   <li>环境变量 {@code MAGI_KEY_FILE} 指向的文件</li>
     *   <li>{@code ~/.magi-eva-key} / {@code ~/Desktop/key.txt} 等常见位置的兜底</li>
     * </ol>
     * 文件里只取第一行非空、且不以 http 开头的行当密钥（同一文件里的 http 行可当地址）。
     */
    private static String readKey() throws Exception {
        String fromEnv = System.getenv("JEV_KEY");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) return fromEnv.trim();

        String home = System.getProperty("user.home");
        String[] cands = {
                System.getenv("MAGI_KEY_FILE"),
                home + "/.magi-eva-key",
                home + "/Desktop/key.txt",
        };
        for (String c : cands) {
            if (c == null) continue;
            java.io.File f = new java.io.File(c);
            if (!f.isFile()) continue;
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("http")) return t;
                }
            } finally {
                r.close();
            }
        }
        throw new IllegalStateException(
                "找不到密钥。请设置环境变量 JEV_KEY，或把密钥作为第二个参数传入，"
                        + "或用 MAGI_KEY_FILE 指向密钥文件。");
    }
}
