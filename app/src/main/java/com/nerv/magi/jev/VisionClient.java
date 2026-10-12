package com.nerv.magi.jev;

import org.json.JSONArray;
import org.json.JSONObject;

import com.nerv.magi.core.Prefs;

/**
 * 视觉路客户端：OpenAI 兼容的多模态 {@code /chat/completions}。
 *
 * <p>与参考实现 {@code jev/VisionClient.kt} 同构。本作当前的询问流程不使用它——
 * 保留完整实现是为了两件事：其一，与「后台基本照抄参考项目」的要求保持一致的三路结构；
 * 其二，让设置页能对视觉路单独做连通性测试，从而在不改动主流程的前提下扩展
 * 「对着屏幕截图询问 MAGI」这类用法。
 */
public class VisionClient {

    private final Prefs prefs;

    public VisionClient(Prefs prefs) {
        this.prefs = prefs;
    }

    /**
     * 送一张 base64 PNG 过去并取回正文。
     *
     * @param prompt 提问
     * @param base64Png 图片（不带 data: 前缀）
     */
    public String describe(String prompt, String base64Png) {
        String url = prefs.visionEndpoint();
        try {
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", prompt);
            content.put(text);

            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", "data:image/png;base64," + base64Png);
            JSONObject image = new JSONObject();
            image.put("type", "image_url");
            image.put("image_url", imageUrl);
            content.put(image);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", content));

            JSONObject body = new JSONObject();
            body.put("model", prefs.visionModel());
            body.put("messages", messages);

            JSONObject resp = HttpJson.post(url, prefs.effectiveVisionKey(), body, Route.VISION,
                    HttpJson.headersFor(url));
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject msg = choices.optJSONObject(0) == null ? null
                    : choices.optJSONObject(0).optJSONObject("message");
            if (msg == null) return "";
            Object c = msg.opt("content");
            if (c instanceof String) return (String) c;
            if (c instanceof JSONArray) {
                JSONArray arr = (JSONArray) c;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject part = arr.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type"))) {
                        return part.optString("text", "");
                    }
                }
            }
            return "";
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(Route.VISION, null, e.getMessage());
        }
    }

    /** 视觉路连通性测试。 */
    public String ping() {
        JSONArray messages = new JSONArray();
        try {
            messages.put(new JSONObject().put("role", "user").put("content", "请只回复两个字：收到"));
            JSONObject body = new JSONObject();
            body.put("model", prefs.visionModel());
            body.put("messages", messages);
            String url = prefs.visionEndpoint();
            JSONObject resp = HttpJson.post(url, prefs.effectiveVisionKey(), body, Route.VISION,
                    HttpJson.headersFor(url));
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject msg = choices.optJSONObject(0) == null ? null
                    : choices.optJSONObject(0).optJSONObject("message");
            return msg == null ? "" : msg.optString("content", "").trim();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(Route.VISION, null, e.getMessage());
        }
    }
}
