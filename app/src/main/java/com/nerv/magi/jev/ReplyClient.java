package com.nerv.magi.jev;

import org.json.JSONArray;
import org.json.JSONObject;

import com.nerv.magi.core.MagiModels;
import com.nerv.magi.core.Prefs;

/**
 * 回复路客户端：任意 OpenAI 兼容的 {@code /chat/completions}。
 *
 * <p>移植自参考实现 {@code jev/ReplyClient.kt}，同样提供三件事：普通往返
 * （{@link #chat}）、连通性测试（{@link #ping}）与摘要/短文本生成（{@link #narrate}）。
 * 密钥留空时按 {@link Prefs#effectiveReplyKey()} 逐级回退到判断路。
 *
 * <p>在本作里这条路是<b>可选</b>的：三贤人的裁决完全由判断路给出，回复路只用来给
 * 每位贤人生成一句短判词。它挂了不影响任何结论。
 */
public class ReplyClient {

    private final Prefs prefs;

    public ReplyClient(Prefs prefs) {
        this.prefs = prefs;
    }

    /**
     * 一次普通 chat 往返，返回助手正文。
     *
     * @throws ApiException 失败
     */
    public String chat(String system, String user, double temperature) {
        String url = prefs.replyEndpoint();
        JSONArray messages = new JSONArray();
        try {
            messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", user));

            JSONObject body = new JSONObject();
            body.put("model", prefs.replyModel());
            body.put("messages", messages);
            body.put("temperature", temperature);

            JSONObject resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY,
                    HttpJson.headersFor(url));
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject msg = choices.optJSONObject(0) == null ? null
                    : choices.optJSONObject(0).optJSONObject("message");
            return msg == null ? "" : msg.optString("content", "");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(Route.REPLY, null, e.getMessage());
        }
    }

    /**
     * 连通性测试：故意的普通往返，而不是 {@link #narrate}——
     * 测试要检验的是日常那条路，不是某个特定提示词。
     */
    public String ping() {
        return chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", 0.0).trim();
    }

    /**
     * 生成一位贤人的一句判词。
     *
     * @param call 出参：耗时与错误写回这里。失败返回空串，绝不抛。
     */
    public String narrate(MagiModels.WiseMan w, String persona, String question,
                          MagiModels.UnitReading unit, MagiModels.Call call) {
        call.route = Route.REPLY;
        call.label = w.label;
        long t0 = System.currentTimeMillis();
        try {
            String sys = JevQuestions.narrationSystemPrompt(w, persona);
            String usr = JevQuestions.narrationUserPrompt(
                    question,
                    unit.verdict == null ? "(未定)" : unit.verdict.choice,
                    unit.verdict == null ? null : unit.verdict.probabilities,
                    unit.verdict == null ? 0 : unit.verdict.confidence,
                    unit.certainty == null ? 0 : unit.certainty.score);
            String out = chat(sys, usr, 0.4).trim();
            call.model = prefs.replyModel();
            call.provider = prefs.replyBaseUrl();
            call.ok = true;
            return out;
        } catch (Exception e) {
            call.ok = false;
            call.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "";
        } finally {
            call.latencyMs = System.currentTimeMillis() - t0;
        }
    }
}
