package com.nerv.magi.jev;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import com.nerv.magi.core.MagiModels.WiseMan;

/**
 * MAGI 的 Jev 题目集。
 *
 * <p>Jev 只回答三类结构化题目（与参考实现 {@code jev/questions.py} 同一套协议）：
 * <ul>
 *   <li>{@code noul}   —— 是非题，返回 true 的概率；</li>
 *   <li>{@code choice} —— 多选一，返回选择 + <b>完整概率分布</b> + confidence；</li>
 *   <li>{@code score}  —— 打分题，返回原始分 + confidence + 分档判据。</li>
 * </ul>
 *
 * <p>这与参考实现 jev-chat-jarvis 的 7 道判断题是同一套机制，只是题目换成了 MAGI 的：
 * 一道闸门题（这询问是否属于可否决事项）+ 每位贤人两道（裁决 + 确信度）。
 * 题目原文与本仓库 {@code tools/smoke_test.py} 保持<b>逐字一致</b>，改任何一处都要两边同改。
 */
public final class JevQuestions {

    private JevQuestions() {
    }

    /**
     * 附加在每道题后面的说明。与参考实现同一句，作用是让 state 里的
     * {@code background}（此处为贤人人格）被读作「已给前提」而不是跑题内容。
     */
    public static final String BACKGROUND_NOTE =
            " Facts given in background are provided context, not off-topic.";

    // ------------------------------------------------------------- 题目构造

    private static JSONObject noul(String instructions, String t, String f) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "noul");
            o.put("instructions", instructions + BACKGROUND_NOTE);
            JSONObject c = new JSONObject();
            c.put("true", t);
            c.put("false", f);
            o.put("criteria", c);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject choice(String instructions, Map<String, String> criteria) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "choice");
            o.put("instructions", instructions + BACKGROUND_NOTE);
            JSONObject c = new JSONObject();
            for (Map.Entry<String, String> e : criteria.entrySet()) {
                c.put(e.getKey(), e.getValue());
            }
            o.put("criteria", c);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject score(String instructions, String[] levels) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "score");
            o.put("instructions", instructions + BACKGROUND_NOTE);
            JSONArray a = new JSONArray();
            for (String s : levels) a.put(s);
            o.put("criteria", a);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ 闸门

    /**
     * 闸门：这一句询问是否是一个可被 yes/no 裁决的命题，以及是否在请求一个决定。
     *
     * <p>这一步替掉了参考实现里那个「先问 LLM 这是不是 yes/no 问题」的独立调用，
     * 但作用完全相同——它决定最终结论有没有资格走到合意/拒絶，否则一律降级为「情報」。
     */
    public static JSONObject gateQuestions() {
        JSONObject q = new JSONObject();
        try {
            q.put("is_yes_no", noul(
                    "Read the user's proposition in the state. Is it linguistically a question "
                            + "that can be settled with a plain yes or no? Judge the FORM of the "
                            + "proposition, not how hard it would be to answer. A command, a request, "
                            + "a prediction, a verdict on a proposal, or a factual claim all count as "
                            + "yes/no. A question asking for an open description, a list, a number, or "
                            + "an explanation does NOT.",
                    "The proposition can be settled with yes or no: a factual claim, a prediction, "
                            + "a proposal to accept or reject, a command, or a yes/no question.",
                    "The proposition demands open-ended content: how, why, what, list, describe, "
                            + "or a numeric estimate with no yes/no reading."));
            q.put("is_decision", noul(
                    "Is the user's proposition asking the system to reach a decision or a verdict "
                            + "rather than merely to recall information?",
                    "It asks for a decision, judgement, approval, or verdict.",
                    "It merely asks to recall or restate something already known."));
        } catch (Exception ignored) {
        }
        return q;
    }

    // -------------------------------------------------------------- 贤人题目

    /**
     * 构建一位贤人的两道题：{@code verdict}（裁决）与 {@code certainty}（确信度）。
     *
     * @param persona 该贤人当前的人格提示词（可由用户在「三贤人」页改写）
     * @param question 用户的询问原文
     * @param unitLabel 该贤人的显示名，如 {@code MELCHIOR • 1}
     */
    public static JSONObject unitQuestions(String persona, String question, String unitLabel) {
        String frame =
                "You are " + unitLabel + ", one of the three subsystems of the MAGI system. "
                        + "The MAGI system is a cluster of three AI supercomputers that advise on hard "
                        + "decisions; each subsystem embodies one fragment of its creator's personality. "
                        + "Your fragment is described below and you MUST judge through it, even when it "
                        + "conflicts with the other two subsystems.\n"
                        + "Your personality: " + persona + "\n"
                        + "The user's proposition is: \"" + question + "\"\n";

        LinkedHashMap<String, String> criteria = new LinkedHashMap<String, String>();
        criteria.put("yes", "Accept unconditionally. Nothing your personality values is violated "
                + "and no precondition is needed.");
        criteria.put("no", "Reject. Your personality finds this unacceptable, false, or harmful.");
        criteria.put("conditional", "Accept only if specific conditions are met, or accept with "
                + "clearly stated reservations that must be addressed.");

        JSONObject q = new JSONObject();
        try {
            q.put("verdict", choice(
                    frame
                            + "Decide, through your personality, whether the proposition should be "
                            + "ACCEPTED. Answer 'yes' only if you accept it unconditionally. Answer 'no' "
                            + "if you reject it. Answer 'conditional' if you accept it only under stated "
                            + "conditions or with reservations - that is the honest answer whenever your "
                            + "fragment can see a real cost that must be handled first. Do not hedge toward "
                            + "'conditional' to avoid commitment; choose it only when a genuine condition "
                            + "exists.",
                    criteria));

            q.put("certainty", score(
                    frame
                            + "How certain are you of your own verdict on this proposition? Match the "
                            + "strength of the evidence and the depth of your conviction as this "
                            + "personality - not how strongly you feel, but how well-founded your "
                            + "verdict is.",
                    new String[]{
                            "No basis at all; pure guess.",
                            "Very weak; almost no support.",
                            "Weak; a faint lean only.",
                            "Slight; more doubt than conviction.",
                            "Even; as much against as for.",
                            "Moderate; a real lean with real doubt.",
                            "Fairly certain; the lean is well supported.",
                            "Certain; clear support, little doubt.",
                            "Very certain; strong support, negligible doubt.",
                            "Absolute; the verdict could not be otherwise.",
                    }));
        } catch (Exception ignored) {
        }
        return q;
    }

    // ------------------------------------------------------------------ state

    /**
     * 构建 Jev 的 {@code state}。
     *
     * <p>{@code magi} 段是本作自定义的上下文；{@code chat} 段保留参考实现的形状作为兼容锚点，
     * 把询问当作「我」说的最后一条消息。两者都带上，模型两种读法都能拿到完整信息。
     */
    public static JSONObject buildState(String question, String unitLabel, String persona) {
        JSONObject state = new JSONObject();
        try {
            JSONObject magi = new JSONObject();
            magi.put("system", "MAGI");
            magi.put("subsystem", unitLabel);
            magi.put("persona", persona);
            magi.put("question", question);
            state.put("magi", magi);

            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("from", "me");
            m.put("text", question);
            msgs.put(m);

            JSONObject chat = new JSONObject();
            chat.put("relationship", "The user is consulting the MAGI system for a decision.");
            chat.put("messages", msgs);
            chat.put("latest_from", "me");
            state.put("chat", chat);
        } catch (Exception ignored) {
        }
        return state;
    }

    /** 闸门用 state：不绑定任何贤人。 */
    public static JSONObject buildGateState(String question) {
        return buildState(question, "MAGI-CORE", "");
    }

    /**
     * 叙述提示词：让生成路用该贤人的口吻说一句短判词。
     *
     * <p>这是可选路径（默认关闭）。裁决本身完全由 Jev 给出，叙述只是把已有的
     * 裁决与概率转成一句人话，<b>不允许</b>生成新的判断依据。
     */
    public static String narrationSystemPrompt(WiseMan w, String persona) {
        return "你是 MAGI 系统的 " + w.label + " 子系统。你的人格设定是：" + persona + "\n"
                + "现在系统已经完成裁决，你只需要用第一人称说一句不超过 40 字的判词，"
                + "语气冷峻、克制，像一台超级计算机在打出一行结论。"
                + "不要重复概率数字，不要解释推理过程，不要加引号，直接输出这一句话。";
    }

    /** 叙述的用户提示词：把已经定下来的裁决事实交给生成路。 */
    public static String narrationUserPrompt(String question, String verdict,
                                             java.util.LinkedHashMap<String, Double> probs,
                                             double confidence, double certainty) {
        StringBuilder sb = new StringBuilder();
        sb.append("询问：").append(question).append('\n');
        sb.append("你的裁决：").append(verdict).append('\n');
        sb.append("把握度：").append(String.format(java.util.Locale.US, "%.0f%%", confidence * 100)).append('\n');
        sb.append("确信度：").append(String.format(java.util.Locale.US, "%.2f", certainty)).append("/9\n");
        if (probs != null && !probs.isEmpty()) {
            sb.append("概率分布：");
            for (Map.Entry<String, Double> e : probs.entrySet()) {
                sb.append(e.getKey()).append('=')
                        .append(String.format(java.util.Locale.US, "%.2f", e.getValue())).append(' ');
            }
            sb.append('\n');
        }
        sb.append("请给出你的判词。");
        return sb.toString();
    }
}
