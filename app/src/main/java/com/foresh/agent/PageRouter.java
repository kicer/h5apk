package com.foresh.agent;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 深链页面路由：只认 {@code agent://open?page=xxx} 中枚举的固定页面。
 *
 * 严禁接受外部传入的完整 URL；页面名 -> 路径的映射由 branding.properties 的
 * deep_link_pages 在编译期写死，运行时无法修改；解析结果仍受限当前生效的服务器 origin。
 */
public final class PageRouter {

    private static final Map<String, String> PAGES = new HashMap<>();

    static {
        String raw = BuildConfig.AGENT_DEEP_LINK_PAGES;
        if (raw != null) {
            for (String item : raw.split(",")) {
                int idx = item.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String name = item.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String path = item.substring(idx + 1).trim();
                if (!name.isEmpty() && !path.isEmpty()) {
                    PAGES.put(name, path);
                }
            }
        }
    }

    private PageRouter() {
    }

    /**
     * 把 page 枚举名解析为白名单内的绝对地址；未知页面或解析失败返回 null。
     */
    public static String resolve(String page) {
        if (page == null) {
            return null;
        }
        String path = PAGES.get(page.trim().toLowerCase(Locale.ROOT));
        if (path == null) {
            return null;
        }
        try {
            // 基于当前生效的启动地址做相对解析，保证结果仍落在白名单 origin 内
            String url = new URI(ServerConfig.startUrl()).resolve(path).toString();
            return UrlPolicy.isAllowed(url) ? url : null;
        } catch (Exception e) {
            return null;
        }
    }
}
