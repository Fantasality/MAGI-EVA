package com.nerv.magi.jev;

import org.json.JSONObject;

import com.nerv.magi.core.MagiModels;
import com.nerv.magi.core.Prefs;

/**
 * 判断路客户端：只做 Jev 决策调用，不生成任何文本。
 *
 * <p>对应参考实现 {@code jev/JudgeClient.kt}。每一次请求都是
 * {@code {model, state, questions}}，回来的是 {@code {answers:{...}, usage:{...}}}。
 * 本类负责把 {@code answers} 解析成本作的数据模型，并把 Jev 直接回报的
 * model / provider / tokens / cost 记进 {@link MagiModels.Call}。
 */
public class JudgeClient {

    private final Prefs prefs;

    public JudgeClient(Prefs prefs) {
        this.prefs = prefs;
    }

    /**
     * 发一次决策请求。
     *
     * @param label     显示名（{@code GATE} 或 {@code MELCHIOR • 1}）
     * @param state     Jev state
     * @param questions 题目集
     * @param call      出参：耗时、错误、用量、模型名都会写回这里
     * @return 解析好的 {@code answers} 对象；失败时返回 {@code null} 且 {@code call.error} 已填
     */
    public JSONObject ask(String label, JSONObject state, JSONObject questions, MagiModels.Call call) {
        call.route = Route.JUDGE;
        call.label = label;
        long t0 = System.currentTimeMillis();
        try {
            JSONObject body = new JSONObject();
            body.put("model", prefs.judgeModel());
            body.put("state", state);
            body.put("questions", questions);

            String url = prefs.judgeEndpoint();
            JSONObject resp = HttpJson.post(url, prefs.judgeKey(), body, Route.JUDGE,
                    HttpJson.headersFor(url));

            call.model = resp.optString("model", prefs.judgeModel());
            call.provider = resp.optString("provider", prefs.judgeProviderLabel());
            readUsage(resp.optJSONObject("usage"), call);

            call.ok = true;
            JSONObject answers = resp.optJSONObject("answers");
            return answers == null ? new JSONObject() : answers;
        } catch (Exception e) {
            call.ok = false;
            call.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return null;
        } finally {
            call.latencyMs = System.currentTimeMillis() - t0;
        }
    }

    // ------------------------------------------------------------ 闸门解析

    /** 闸门：问「这询问是否属于可否决事项」。 */
    public MagiModels.Gate gate(String question) {
        MagiModels.Gate g = new MagiModels.Gate();
        JSONObject answers = ask("GATE", JevQuestions.buildGateState(question),
                JevQuestions.gateQuestions(), g.call);
        if (answers == null) {
            return g;
        }
        g.yesNo = noulValue(answers.optJSONObject("is_yes_no"), 0.0);
        g.decision = noulValue(answers.optJSONObject("is_decision"), 0.0);
        g.isYesNo = g.yesNo >= 0.5;
        g.isDecision = g.decision >= 0.5;
        return g;
    }

    // ---------------------------------------------------------- 贤人读数解析

    /**
     * 为一位贤人取回裁决与确信度，并就地写进 {@code unit}。
     * 失败时 {@code unit.call.error} 已填，调用方据此把状态置为 {@link MagiModels.Status#ERROR}。
     */
    public void fillUnit(MagiModels.UnitReading unit, String question) {
        JSONObject answers = ask(unit.label, JevQuestions.buildState(question, unit.label, unit.persona),
                JevQuestions.unitQuestions(unit.persona, question, unit.label), unit.call);
        if (answers == null) return;

        unit.verdict = parseChoice(answers.optJSONObject("verdict"));
        unit.certainty = parseScore(answers.optJSONObject("certainty"));
    }

    // ------------------------------------------------------------ 结构解析

    /** Jev {@code noul} 答案：{@code {"type":"noul","noul":0.82}}。 */
    public static double noulValue(JSONObject o, double def) {
        if (o == null) return def;
        return o.optDouble("noul", def);
    }

    /** Jev {@code choice} 答案。 */
    public static MagiModels.Choice parseChoice(JSONObject o) {
        if (o == null) return null;
        MagiModels.Choice c = new MagiModels.Choice();
        c.choice = o.optString("choice", "");
        c.confidence = o.optDouble("confidence", 0.0);
        JSONObject p = o.optJSONObject("probabilities");
        if (p != null) {
            java.util.Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String k = it.next();
                c.probabilities.put(k, p.optDouble(k, 0.0));
            }
        }
        return c;
    }

    /**
     * Jev {@code score} 答案。
     *
     * <p>{@code legend} 在不同供应商下的形状不保证一致：可能缺席、可能是
     * {@code {"0":"...","9":"..."}}，也可能已经是数组。三种都吃，解析不出就退回默认 9 档。
     */
    public static MagiModels.Score parseScore(JSONObject o) {
        if (o == null) return null;
        MagiModels.Score s = new MagiModels.Score();
        s.score = o.optDouble("score", 0.0);
        s.confidence = o.optDouble("confidence", 0.0);

        JSONObject legend = o.optJSONObject("legend");
        if (legend != null && legend.length() > 0) {
            int max = 0;
            java.util.Iterator<String> it = legend.keys();
            java.util.TreeMap<Integer, String> sorted = new java.util.TreeMap<Integer, String>();
            while (it.hasNext()) {
                String k = it.next();
                Integer idx = tryInt(k);
                if (idx != null) {
                    sorted.put(idx, legend.optString(k, ""));
                    if (idx > max) max = idx;
                }
            }
            if (max > 0) s.maxLevel = max;
            for (java.util.Map.Entry<Integer, String> e : sorted.entrySet()) {
                s.legend.add(e.getKey() + "  " + e.getValue());
            }
        } else {
            org.json.JSONArray arr = o.optJSONArray("criteria");
            if (arr == null) arr = o.optJSONArray("legend");
            if (arr != null && arr.length() > 0) {
                for (int i = 0; i < arr.length(); i++) s.legend.add(i + "  " + arr.optString(i, ""));
                s.maxLevel = Math.max(1, arr.length() - 1);
            }
        }
        return s;
    }

    private static Integer tryInt(String s) {
        try {
            return Integer.valueOf(Integer.parseInt(s.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 Jev 的 usage 段读进 {@link MagiModels.Usage}。 */
    private static void readUsage(JSONObject u, MagiModels.Call call) {
        if (u == null) return;
        call.usage.inputTokens = u.optInt("input_tokens", u.optInt("prompt_tokens", 0));
        call.usage.outputTokens = u.optInt("output_tokens", u.optInt("completion_tokens", 0));
        call.usage.cost = u.optDouble("cost", 0.0);
    }
}
