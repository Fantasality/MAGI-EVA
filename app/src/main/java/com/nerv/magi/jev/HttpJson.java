package com.nerv.magi.jev;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 共用的 POST-JSON 助手。
 *
 * <p>逐行移植自参考实现 {@code jev/HttpJson.kt}，行为一字不改：
 * <ul>
 *   <li>UTF-8 请求体；</li>
 *   <li>429 / 529 指数退避重试（最多 {@value #MAX_ATTEMPTS} 次），其它 4xx <b>不</b>重试；</li>
 *   <li>所有失败归一化为 {@link ApiException}，密钥只在调用时传入、绝不进日志。</li>
 * </ul>
 *
 * <p>关键细节（参考实现里用注释强调过，这里同样保留）：<b>先判状态码，再读响应体</b>。
 * 某些失败下 {@code errorStream} 为 null，读取还可能抛异常，若先读体就会把 401 误报成
 * 传输失败，从而被重试。
 */
public final class HttpJson {

    private HttpJson() {
    }

    /** 最多尝试次数。 */
    public static final int MAX_ATTEMPTS = 3;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    /**
     * POST 一个 JSON 体，返回解析好的响应。
     *
     * @param url          完整地址
     * @param key          Bearer 密钥
     * @param body         请求体
     * @param route        {@link Route} 之一，只用于错误文案
     * @param extraHeaders 额外请求头（例如 OpenRouter 的归因头）
     * @throws ApiException 任何失败
     */
    public static JSONObject post(String url, String key, JSONObject body, String route,
                                 Map<String, String> extraHeaders) {
        Map<String, String> headers = extraHeaders == null ? Collections.<String, String>emptyMap() : extraHeaders;
        ApiException last = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }

                byte[] bytes = body.toString().getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(bytes);
                    os.flush();
                } finally {
                    try { os.close(); } catch (Exception ignored) { }
                }

                int code = conn.getResponseCode();

                if (code == 429 || code == 529) {
                    last = new ApiException(route, code, "服务繁忙，已重试");
                    sleepBackoff(attempt);
                    continue;
                }

                if (code < 200 || code > 299) {
                    String errText = readBody(conn.getErrorStream());
                    String snippet = errText.trim().isEmpty() ? "（响应体为空）" : errText;
                    ApiException e = new ApiException(route, code, snippet);
                    if (e.isClientError()) throw e;   // 4xx 不重试
                    last = e;
                    sleepBackoff(attempt);
                    continue;
                }

                String text = readBody(conn.getInputStream());
                if (text.trim().isEmpty()) {
                    last = new ApiException(route, code, "响应体为空");
                    sleepBackoff(attempt);
                    continue;
                }
                return new JSONObject(text);

            } catch (ApiException e) {
                if (e.isClientError()) throw e;
                last = e;
                sleepBackoff(attempt);
            } catch (Exception e) {
                last = new ApiException(route, null, describe(e));
                sleepBackoff(attempt);
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) { }
                }
            }
        }
        throw last != null ? last : new ApiException(route, null, "请求失败");
    }

    /** 便捷重载：无额外请求头。 */
    public static JSONObject post(String url, String key, JSONObject body, String route) {
        return post(url, key, body, route, headersFor(url));
    }

    /** 退避：0.5s / 1s，与参考实现一致。最后一次失败后不再等待。 */
    private static void sleepBackoff(int attempt) {
        if (attempt + 1 >= MAX_ATTEMPTS) return;
        try {
            Thread.sleep(500L * (1L << (attempt + 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 响应体文本；流为 null 或读取失败一律返回空串——<b>状态码绝不能因为读体失败而丢掉</b>。
     */
    private static String readBody(InputStream stream) {
        if (stream == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * OpenRouter 要求归因头；其它主机对未知头会礼貌忽略。
     */
    public static Map<String, String> headersFor(String url) {
        if (url != null && url.toLowerCase().contains("openrouter.ai")) {
            HashMap<String, String> m = new HashMap<String, String>();
            m.put("HTTP-Referer", "https://magi-eva.local");
            m.put("X-Title", "MAGI EVA");
            return m;
        }
        return Collections.emptyMap();
    }

    /** 把传输层异常翻译成人话（绝不包含密钥材料）。 */
    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        String lower = m.toLowerCase();
        if (lower.contains("timed out") || lower.contains("timeout")) return "网络超时，请检查连接";
        if (lower.contains("unable to resolve host")) return "域名解析失败，地址填错或无网络";
        if (lower.contains("failed to connect") || lower.contains("econnrefused")) return "无法连接该地址";
        if (lower.contains("certpath") || lower.contains("ssl")) return "HTTPS 证书校验失败";
        return m;
    }
}
