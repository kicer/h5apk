package com.foresh.agent;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * 服务器地址校验：请求 {@code <origin>/api/health}，要求
 * HTTP 200 + JSON 可解析 + {@code ok===true} + {@code service==='agent-core'}。
 *
 * <p>用原生 HttpURLConnection 而不是 WebView：没有 CORS、不会闪一下裸 JSON、
 * 也不会把检查请求写进主 WebView 的历史栈。请求本身在子线程，回调切回主线程。
 */
public final class ServerProbe {

    private static final int TIMEOUT_MS = 6000;
    private static final int MAX_BODY = 64 * 1024;

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ServerProbe() {
    }

    /** @param baseUrl 服务器 origin（也接受带路径的默认地址，内部会收敛到 origin） */
    public static void check(final String baseUrl, final Callback callback) {
        final String url = ServerConfig.healthUrl(baseUrl);
        new Thread(() -> {
            boolean ok;
            String message;
            try {
                Result result = doCheck(url);
                ok = result.ok;
                message = result.message;
            } catch (Exception e) {
                ok = false;
                message = describe(e);
            }
            final boolean resultOk = ok;
            final String resultMessage = message;
            MAIN.post(() -> callback.onResult(resultOk, resultMessage));
        }, "server-probe").start();
    }

    private static Result doCheck(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "AgentShell/1.0");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return new Result(false,
                            "地址能连上，但没有 /api/health（检查端口，或反向代理的子路径配置）");
                }
                if (code >= 300 && code < 400) {
                    return new Result(false,
                            "服务器要求跳转（HTTP " + code + "），请直接填写跳转后的地址");
                }
                return new Result(false, "服务器返回 HTTP " + code);
            }

            String body = readLimited(conn.getInputStream());
            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (Exception e) {
                return new Result(false, "返回的不是 JSON，可能填到了别的站点或首页");
            }
            if (!"agent-core".equals(json.optString("service"))) {
                return new Result(false, "这个地址不是 agent-core 服务");
            }
            if (!json.optBoolean("ok", false)) {
                return new Result(false, "服务已连上，但未就绪（ok 不为 true）");
            }
            return new Result(true, "");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readLimited(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream input = in;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = input.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) {
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    /** 把异常翻译成用户能看懂、且能指向下一步的说法。 */
    private static String describe(Exception e) {
        if (e instanceof UnknownHostException) {
            return "找不到这个域名（检查地址拼写或网络）";
        }
        if (e instanceof NoRouteToHostException) {
            return "无法路由到该地址（检查 IP 和本机网络）";
        }
        if (e instanceof ConnectException) {
            return "连不上服务器（检查 IP、端口，以及服务是否启动）";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接超时（检查网络，或端口是否被防火墙挡住）";
        }
        if (e instanceof SSLException) {
            return "HTTPS 证书不受信任（自签名证书请改用 http://）";
        }
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("cleartext")) {
            return "系统禁止明文 HTTP（请改用 https://）";
        }
        return "检查失败：" + (msg == null || msg.isEmpty()
                ? e.getClass().getSimpleName() : msg);
    }

    private static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }
}
