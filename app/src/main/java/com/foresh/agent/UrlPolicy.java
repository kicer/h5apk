package com.foresh.agent;

import android.net.Uri;

/**
 * 地址白名单：只允许编译期写死的「HTTP 固定 IP:端口」或「HTTPS 固定域名」。
 *
 * 所有 WebView 加载、跳转、重定向、下载、上传以及子资源请求都必须先过这里，
 * 不在白名单内的地址一律拦截，绝不交给外部应用打开。
 */
public final class UrlPolicy {

    private UrlPolicy() {
    }

    /** 是否是应用允许加载的地址。 */
    public static boolean isAllowed(String raw) {
        if (raw == null) {
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            // http_host 为空 = 未配置 HTTP 直连，一律拒绝
            return host.equalsIgnoreCase(BuildConfig.AGENT_HTTP_HOST)
                    && effectivePort(uri.getPort(), 80) == BuildConfig.AGENT_HTTP_PORT;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return host.equalsIgnoreCase(BuildConfig.AGENT_HTTPS_HOST)
                    && effectivePort(uri.getPort(), 443) == BuildConfig.AGENT_HTTPS_PORT;
        }
        // data: / blob: / about: 等交给 WebView 默认处理；其余协议（tel/mailto/intent 等）不放行
        return false;
    }

    private static int effectivePort(int port, int defaultPort) {
        return port == -1 ? defaultPort : port;
    }
}
