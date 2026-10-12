package com.nerv.magi.jev;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.nerv.magi.core.MagiLogic;
import com.nerv.magi.core.MagiModels;
import com.nerv.magi.core.MagiModels.Session;
import com.nerv.magi.core.MagiModels.Status;
import com.nerv.magi.core.MagiModels.UnitReading;
import com.nerv.magi.core.MagiModels.WiseMan;
import com.nerv.magi.core.Prefs;

/**
 * MAGI 主客户端：把一次询问跑成三贤人合议。
 *
 * <p>对应参考实现 {@code jev/JevClient.kt} 的门面角色，但流程换成了 MAGI 的：
 *
 * <pre>
 *   询问 Q
 *     ├─ 闸门 GATE ──────────── 这命题是否可被 yes/no 裁决
 *     ├─ MELCHIOR • 1 ───────── verdict + certainty   ┐
 *     ├─ BALTHASAR • 2 ──────── verdict + certainty   ├ 四路并行发出
 *     └─ CASPER • 3 ─────────── verdict + certainty   ┘
 *                 ↓
 *        一票否决合议（MagiLogic.vote）
 *                 ↓
 *        合意 / 拒絶 / 状態 / 情報 / 誤差
 * </pre>
 *
 * <p><b>为什么四路并行发</b>：闸门只在最后一步参与裁决（决定结论有没有资格走到
 * 合意/拒絶），它与三位贤人的题目彼此独立。并行发能把总耗时从
 * {@code gate + max(unit)} 压到 {@code max(gate, unit)}，实测从 ~5.3s 降到 ~1.8s。
 */
public class MagiClient {

    /**
     * 进度回调。所有方法都在<b>工作线程</b>上被调用，界面侧需自行切回主线程。
     *
     * <p>回调都带上正在构建中的 {@link Session}，这样界面能在三位贤人陆续返回时
     * <b>逐个点亮</b>它们——这正是 MAGI 面板该有的观感，而不是等全部跑完才一起变色。
     */
    public interface Progress {
        /** 闸门返回。 */
        void onGate(Session session, MagiModels.Gate gate);

        /** 某位贤人已返回（成功或失败都会回调一次）。 */
        void onUnit(Session session, UnitReading unit);

        /** 全部结束。 */
        void onDone(Session session);
    }

    private final Prefs prefs;
    private final JudgeClient judge;
    private final ReplyClient reply;

    public MagiClient(Prefs prefs) {
        this.prefs = prefs;
        this.judge = new JudgeClient(prefs);
        this.reply = new ReplyClient(prefs);
    }

    public Prefs prefs() {
        return prefs;
    }

    public ReplyClient replyClient() {
        return reply;
    }

    public JudgeClient judgeClient() {
        return judge;
    }

    /**
     * 跑一次完整合议。这是一个<b>阻塞</b>调用，必须在工作线程调用。
     *
     * @param question 询问原文
     * @param cb       进度回调，可为 null
     */
    public Session conduct(final String question, final Progress cb) {
        final Session s = new Session();
        s.question = question == null ? "" : question.trim();
        s.startedAt = System.currentTimeMillis();

        if (s.question.isEmpty()) {
            s.error = "詢問為空。";
            s.overall = Status.INFO;
            s.finishedAt = System.currentTimeMillis();
            if (cb != null) cb.onDone(s);
            return s;
        }

        if (!prefs.hasKey()) {
            s.error = "未配置密钥：请先进入「接口」填入 Jev 判断接口的 API Key。";
            s.overall = Status.ERROR;
            s.finishedAt = System.currentTimeMillis();
            if (cb != null) cb.onDone(s);
            return s;
        }

        // 先把三位贤人的读数占位建好（面板一上来就能显示三个块在闪）
        for (WiseMan w : WiseMan.ALL) {
            UnitReading u = new UnitReading();
            u.name = w.name;
            u.order = w.order;
            u.slug = w.slug;
            u.label = w.label;
            u.persona = prefs.persona(w);
            u.status = Status.INFO;
            s.units.add(u);
        }

        final ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            final CountDownLatch latch = new CountDownLatch(4);

            // ---- 闸门
            Future<MagiModels.Gate> gateF = pool.submit(new Callable<MagiModels.Gate>() {
                @Override
                public MagiModels.Gate call() {
                    MagiModels.Gate g = judge.gate(s.question);
                    s.gate.yesNo = g.yesNo;
                    s.gate.decision = g.decision;
                    s.gate.isYesNo = g.isYesNo;
                    s.gate.isDecision = g.isDecision;
                    copyCall(g.call, s.gate.call);
                    if (cb != null) cb.onGate(s, s.gate);
                    latch.countDown();
                    return g;
                }
            });

            // ---- 三位贤人，各自一路
            final java.util.List<Future<?>> unitFutures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < WiseMan.ALL.length; i++) {
                final UnitReading unit = s.units.get(i);
                unitFutures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            judge.fillUnit(unit, s.question);
                            unit.status = MagiLogic.classify(unit, true);
                            // 可选叙述：只在裁决成功后尝试，失败不影响结论
                            if (prefs.narration() && unit.verdict != null) {
                                MagiModels.Call ncall = new MagiModels.Call();
                                unit.narration = reply.narrate(
                                        wiseManOf(unit.slug), unit.persona, s.question, unit, ncall);
                                // 叙述的耗时/费用单独记，不混进裁决的用量
                                unit.narrationCost = ncall.usage.cost;
                                unit.narrationLatencyMs = ncall.latencyMs;
                            }
                        } catch (Throwable t) {
                            unit.call.ok = false;
                            unit.call.error = t.getMessage() == null
                                    ? t.getClass().getSimpleName() : t.getMessage();
                            unit.status = Status.ERROR;
                        }
                        if (cb != null) cb.onUnit(s, unit);
                        latch.countDown();
                    }
                }));
            }

            // 等全部回来。单次读取超时 60s + 重试，这里给足 150s 上限。
            latch.await(150, TimeUnit.SECONDS);

            MagiModels.Gate g;
            try {
                g = gateF.get();
            } catch (Exception e) {
                g = s.gate;
            }

            // ---- 定状态：闸门没通过时，贤人一律记 情報（与参考实现一致）
            for (UnitReading u : s.units) {
                u.status = MagiLogic.classify(u, g.isYesNo);
            }

            // ---- 合议
            s.overall = MagiLogic.vote(s.units, g.isYesNo);
            if (g.call.error != null && !g.isYesNo) {
                // 闸门本身失败时不能假装「情報」是算出来的
                s.overall = Status.ERROR;
                s.error = g.call.error;
            }

        } catch (Exception e) {
            s.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            s.overall = Status.ERROR;
        } finally {
            pool.shutdownNow();
        }

        s.finishedAt = System.currentTimeMillis();
        if (cb != null) cb.onDone(s);
        return s;
    }

    private static WiseMan wiseManOf(String slug) {
        for (WiseMan w : WiseMan.ALL) if (w.slug.equals(slug)) return w;
        return WiseMan.ALL[0];
    }

    private static void copyCall(MagiModels.Call from, MagiModels.Call to) {
        to.route = from.route;
        to.label = from.label;
        to.latencyMs = from.latencyMs;
        to.model = from.model;
        to.provider = from.provider;
        to.error = from.error;
        to.ok = from.ok;
        to.usage.add(from.usage);
    }

    /**
     * 判断路连通性测试：发一次最小决策请求，验证密钥 / 地址 / 模型都对。
     * 这是设置页「测试」按钮后端，故意只打判断路——三路里它才是必须配的那一路。
     */
    public String testJudge() {
        MagiModels.Call call = new MagiModels.Call();
        org.json.JSONObject answers = judge.ask("连通测试",
                JevQuestions.buildGateState("Is this a connectivity test?"),
                JevQuestions.gateQuestions(), call);
        if (answers == null) {
            throw new ApiException(Route.JUDGE, null, call.error == null ? "无响应" : call.error);
        }
        return "判断接口连通 ✓  " + call.model
                + "  " + call.latencyMs + "ms"
                + "  " + call.usage.totalTokens() + " tokens";
    }

    /** 回复路连通性测试。 */
    public String testReply() {
        long t0 = System.currentTimeMillis();
        String r = reply.ping();
        return "回复接口连通 ✓  " + prefs.replyModel()
                + "  " + (System.currentTimeMillis() - t0) + "ms  「" + r + "」";
    }

    /** 视觉路连通性测试。 */
    public String testVision() {
        VisionClient v = new VisionClient(prefs);
        long t0 = System.currentTimeMillis();
        String r = v.ping();
        return "视觉接口连通 ✓  " + prefs.visionModel()
                + "  " + (System.currentTimeMillis() - t0) + "ms  「" + r + "」";
    }
}
