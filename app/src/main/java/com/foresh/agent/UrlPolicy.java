package com.foresh.agent;

import android.net.Uri;

/**
 * 地址白名单：只允许当前生效的服务器 origin（scheme + host + port）。
 *
 * <p>默认 origin 来自编译期配置；用户在首次启动页手动填写后，只放行他填的那一个 origin。
 * 所有 WebView 加载、跳转、重定向、下载、上传以及子资源请求都必须先过这里；
 * http(s) 之外的协议（tel/mailto/intent/file 等）一律不放行。
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
        if (scheme == null) {
            return false;
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            // data: / blob: / about: 等交给 WebView 默认处理；其余协议不放行
            return false;
        }
        return ServerConfig.isAllowed(uri);
    }
}
