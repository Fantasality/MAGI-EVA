package com.nerv.magi.jev;

/**
 * 三路接口之一出错时抛出的统一异常。
 *
 * <p>移植自参考实现 {@code jev/HttpJson.kt}。带上出错的是哪一路、HTTP 状态码
 * （{@code null} 表示传输层失败）以及响应体前 120 字，这样设置页能把真实原因原样
 * 显示出来（「判断接口 HTTP 401：…」而不是「网络错误」）。
 */
public class ApiException extends RuntimeException {

    /** 出错的路由名，取 {@link Route} 之一。 */
    public final String route;
    /** HTTP 状态码；传输层失败时为 null。 */
    public final Integer status;
    /** 响应体片段。 */
    public final String snippet;

    public ApiException(String route, Integer status, String snippet) {
        super(buildMessage(route, status, snippet));
        this.route = route;
        this.status = status;
        this.snippet = snippet == null ? "" : snippet;
    }

    public static String buildMessage(String route, Integer status, String snippet) {
        String s = snippet == null ? "" : snippet;
        if (s.length() > 120) s = s.substring(0, 120);
        return status != null
                ? route + " HTTP " + status + "：" + s
                : route + " 请求失败：" + s;
    }

    /** 客户端错误（4xx）不重试，其余重试。 */
    public boolean isClientError() {
        return status != null && status >= 400 && status <= 499;
    }
}
