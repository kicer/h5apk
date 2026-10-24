package com.foresh.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 服务器地址：默认用编译期写死的 {@link BuildConfig#AGENT_FIXED_URL}，
 * 首次启动时用户可手动填写一个 origin，之后长期生效。
 *
 * <p>手动配置只记 scheme + host + port（不接受路径/参数），运行时也只放行这一个 origin，
 * 所以壳仍然是「单站点应用」，不会变成能打开任意网址的浏览器。
 */
public final class ServerConfig {

    /** 手动输入没写端口时，http 用这个端口。 */
    public static final int DEFAULT_HTTP_PORT = 8090;

    private static final String PREFS_NAME = "server_config";
    private static final String KEY_ORIGIN = "origin";
    private static final String KEY_SETUP_DONE = "setup_done";

    private static Context appContext;
    private static volatile List<Origin> allowed = Collections.emptyList();

    private ServerConfig() {
    }

    /** 必须在任何 {@link UrlPolicy} 调用之前执行（MainActivity.onCreate 最前面）。 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        refresh();
    }

    // ===== 首次设置 =====

    public static boolean isSetupDone() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_SETUP_DONE, false);
    }

    public static void setSetupDone() {
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putBoolean(KEY_SETUP_DONE, true).apply();
        }
    }

    /** 用户保存的 origin；没有手动配置时为空串。 */
    public static String savedOrigin() {
        SharedPreferences p = prefs();
        return p == null ? "" : p.getString(KEY_ORIGIN, "");
    }

    public static boolean hasOverride() {
        return !savedOrigin().isEmpty();
    }

    /** 当前生效的启动地址（手动配置优先，否则编译期默认）。 */
    public static String startUrl() {
        Origin origin = parse(savedOrigin());
        return origin != null ? origin.baseUrl() + "/" : BuildConfig.AGENT_FIXED_URL;
    }

    public static void saveOrigin(String normalizedOrigin) {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        p.edit().putString(KEY_ORIGIN, normalizedOrigin).apply();
        refresh();
    }

    public static void clearOverride() {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        p.edit().remove(KEY_ORIGIN).apply();
        refresh();
    }

    // ===== 运行时白名单 =====

    /** 允许加载的 origin：手动配置时只有它自己；默认配置时是默认地址 + 备用地址。 */
    public static List<Origin> allowedOrigins() {
        return allowed;
    }

    public static boolean isAllowed(Uri uri) {
        if (uri == null) {
            return false;
        }
        for (Origin origin : allowed) {
            if (origin.matches(uri)) {
                return true;
            }
        }
        return false;
    }

    // ===== 输入解析 =====

    /**
     * 解析设置页输入。
     *
     * @return 规范化后的 origin；输入为空时返回 {@code null}（表示使用默认地址）
     * @throws IllegalArgumentException 输入非法，message 可直接展示给用户
     */
    public static String normalizeUserInput(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            return null;
        }
        if (input.indexOf(' ') >= 0 || input.indexOf('\t') >= 0
                || input.indexOf('@') >= 0 || input.indexOf('?') >= 0
                || input.indexOf('#') >= 0 || input.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("只填 IP 或域名，不要带路径、账号或参数");
        }

        String withScheme = input.contains("://") ? input : "http://" + input;
        Uri uri = Uri.parse(withScheme);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("地址格式不正确");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("只支持 http:// 或 https://");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("不要带账号密码");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("地址格式不正确");
        }
        if (host.indexOf(':') >= 0) {
            throw new IllegalArgumentException("暂不支持 IPv6 地址");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("只填 IP 或域名，不要带路径");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("只填 IP 或域名，不要带参数");
        }

        int port = uri.getPort();
        if (port == -1) {
            // 写了 :端口 但解析失败（如 99999）时 Uri 会返回 -1，不能默默换成默认端口
            String authority = uri.getAuthority();
            if (authority != null && authority.indexOf(':') >= 0) {
                throw new IllegalArgumentException("端口不正确（1~65535）");
            }
            port = "https".equals(scheme) ? 443 : DEFAULT_HTTP_PORT;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口不正确（1~65535）");
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    // ===== 内部 =====

    private static SharedPreferences prefs() {
        return appContext == null ? null
                : appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void refresh() {
        List<Origin> list = new ArrayList<>();
        Origin saved = parse(savedOrigin());
        if (saved != null) {
            list.add(saved);
        } else {
            Origin fixed = parse(BuildConfig.AGENT_FIXED_URL);
            Origin fallback = parse(BuildConfig.AGENT_FALLBACK_URL);
            if (fixed != null) {
                list.add(fixed);
            }
            if (fallback != null && !fallback.sameAs(fixed)) {
                list.add(fallback);
            }
        }
        allowed = Collections.unmodifiableList(list);
    }

    /** 解析内部地址（默认/备用/已保存的 origin），失败返回 null。 */
    private static Origin parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Uri uri = Uri.parse(raw);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return new Origin(scheme, host.toLowerCase(Locale.ROOT), port);
    }

    /** 一个站点：scheme + host + port。 */
    public static final class Origin {
        public final String scheme;
        public final String host;
        public final int port;

        Origin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        boolean matches(Uri uri) {
            String uriScheme = uri.getScheme();
            String uriHost = uri.getHost();
            if (uriScheme == null || uriHost == null) {
                return false;
            }
            if (!scheme.equalsIgnoreCase(uriScheme) || !host.equalsIgnoreCase(uriHost)) {
                return false;
            }
            int uriPort = uri.getPort();
            if (uriPort == -1) {
                uriPort = "https".equalsIgnoreCase(uriScheme) ? 443 : 80;
            }
            return port == uriPort;
        }

        boolean sameAs(Origin other) {
            return other != null && scheme.equals(other.scheme)
                    && host.equals(other.host) && port == other.port;
        }

        public String baseUrl() {
            int defaultPort = "https".equals(scheme) ? 443 : 80;
            return port == defaultPort ? scheme + "://" + host
                    : scheme + "://" + host + ":" + port;
        }

        @Override
        public String toString() {
            return baseUrl();
        }
    }
}
