package com.foresh.agent;

import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.HashMap;

/**
 * H5 壳：地址在编译期写死（BuildConfig），运行时无法修改，也不提供任何网址输入入口。
 * 所有加载/跳转/重定向/下载/上传/子资源都必须过 {@link UrlPolicy} 白名单。
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private View loadingOverlay;
    private ProgressBar progressBar;
    private TextView loadingText;
    private TextView countdownText;
    private View errorView;
    private TextView errorTitle;
    private TextView errorMessage;
    private TextView retryCountdown;
    private Button retryButton;

    private final Handler timeoutHandler = new Handler();
    private static final int LOAD_TIMEOUT = 15000; // 15秒超时
    private CountDownTimer retryTimer;
    private static final int RETRY_DELAY = 10000; // 10秒后重试

    // 当前加载地址：只会是白名单 origin 内的地址
    private String currentUrl;
    // 启动地址失败后是否已尝试过备用地址（避免反复横跳）
    private boolean fallbackAttempted = false;

    // ===== 文件上传相关 =====
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_RESULT_CODE = 100;

    // ===== 服务器地址设置 =====
    private static final int REQ_SETUP = 200;

    // ===== 语音录音桥 =====
    private VoiceBridge voiceBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须先于任何 UrlPolicy 调用：这里加载「允许的 origin」白名单
        ServerConfig.init(this);
        setContentView(R.layout.activity_main);

        applyStatusBarColor();
        initViews();
        setupWebView();

        if (!ServerConfig.isSetupDone()) {
            // 首次启动：先让用户填服务器地址（可留空走默认），回来后再加载页面
            startActivityForResult(new Intent(this, ServerSetupActivity.class)
                    .putExtra(ServerSetupActivity.EXTRA_FIRST_RUN, true), REQ_SETUP);
            return;
        }
        loadStartPage();
    }

    /** 启动地址：深链枚举页 > 当前生效的服务器地址（手动配置优先，否则编译期默认）。 */
    private void loadStartPage() {
        String deepLink = resolveDeepLink(getIntent());
        loadUrl(deepLink != null ? deepLink : ServerConfig.startUrl());
    }

    /**
     * 只解析 agent://open?page=xxx，返回枚举页面对应的白名单地址。
     * 绝不读取 url 参数，绝不接受外部传入的完整网址。
     */
    private String resolveDeepLink(Intent intent) {
        if (intent == null) {
            return null;
        }
        Uri data = intent.getData();
        if (data == null) {
            return null;
        }
        if (!"agent".equalsIgnoreCase(data.getScheme())) {
            return null;
        }
        if (!"open".equalsIgnoreCase(data.getHost())) {
            return null;
        }
        return PageRouter.resolve(data.getQueryParameter("page"));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!ServerConfig.isSetupDone()) {
            return; // 首次设置还没完成，等设置页返回后再处理
        }
        String deepLink = resolveDeepLink(intent);
        if (deepLink != null) {
            loadUrl(deepLink);
        }
    }

    private void initViews() {
        webView = findViewById(R.id.webview);
        loadingOverlay = findViewById(R.id.loading_overlay);
        progressBar = findViewById(R.id.progress_bar);
        loadingText = findViewById(R.id.loading_text);
        countdownText = findViewById(R.id.countdown_text);
        errorView = findViewById(R.id.error_view);
        errorTitle = findViewById(R.id.error_title);
        errorMessage = findViewById(R.id.error_message);
        retryCountdown = findViewById(R.id.retry_countdown);
        retryButton = findViewById(R.id.retry_button);

        retryButton.setOnClickListener(v -> {
            cancelRetryTimer();
            hideErrorView();
            if (currentUrl != null) {
                loadUrl(currentUrl);
            }
        });
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // ===== WebView 安全收紧 =====
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Cookie：保留本域登录态，禁止第三方 Cookie
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        // JS 桥只暴露语音录音（3 个无参方法，无文件/存储读取能力），契约见 voice-input.md
        voiceBridge = new VoiceBridge(this, webView);
        // 必须在 loadUrl 之前注册，否则首屏页面拿不到 window.AgentNative
        webView.addJavascriptInterface(voiceBridge, "AgentNative");

        webView.setWebViewClient(new CustomWebViewClient());

        // 文件上传：只有白名单页面才允许唤起系统选择器
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (!UrlPolicy.isAllowed(view.getUrl())) {
                    return false;
                }
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = callback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    Intent picIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    picIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    picIntent.setType("image/*");
                    startActivityForResult(
                            Intent.createChooser(picIntent, "选择图片"), FILE_CHOOSER_RESULT_CODE);
                }
                return true;
            }
        });

        // 文件下载：地址必须过白名单；失败不做「转交系统浏览器」等兜底跳转
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (!UrlPolicy.isAllowed(url)) {
                Toast.makeText(this, "下载地址不被允许", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setTitle(fileName);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(this, "开始下载，进度见通知栏", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETUP) {
            if (resultCode == RESULT_OK) {
                // 服务器地址可能变了：旧 origin 的历史记录不能留着
                webView.clearHistory();
                currentUrl = null;
                loadStartPage();
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) {
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    results = new Uri[]{uri};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (voiceBridge != null) {
            voiceBridge.onRequestPermissionsResult(requestCode, grantResults);
        }
    }

    /** 加载地址：必须过白名单，否则直接报错，不做任何外部跳转。 */
    private void loadUrl(String url) {
        if (!UrlPolicy.isAllowed(url)) {
            currentUrl = null;
            showError("无法打开", "目标地址不在应用允许的范围内");
            return;
        }
        currentUrl = url;
        fallbackAttempted = false;
        showLoadingState();
        startLoadTimeoutTimer();
        webView.loadUrl(url);
    }

    /**
     * 顶层导航处理：返回 true 表示不在 WebView 内加载（由本方法接管）。
     * 白名单内地址留在 WebView；外部 http(s) 按开关交给系统浏览器；其余协议拦截。
     */
    private boolean handleNavigation(Uri uri) {
        if (UrlPolicy.isAllowed(uri.toString())) {
            return false;
        }
        if (BuildConfig.AGENT_OPEN_EXTERNAL_LINKS) {
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                openInBrowser(uri);
            }
        }
        return true;
    }

    /** 外部链接交给系统浏览器；只接受 http(s)，不把任意协议抛给外部应用。 */
    private void openInBrowser(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "未找到可打开该链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 启动地址失败时切换到备用地址（只尝试一次；手动配置的地址不自动切换）。 */
    private boolean tryFallback() {
        if (ServerConfig.hasOverride()) {
            // 用户明确填了地址，填错就让他改，不要静默换到别的服务器
            return false;
        }
        String fallback = BuildConfig.AGENT_FALLBACK_URL;
        if (fallbackAttempted || fallback == null || fallback.isEmpty()) {
            return false;
        }
        if (fallback.equals(currentUrl) || !UrlPolicy.isAllowed(fallback)) {
            return false;
        }
        fallbackAttempted = true;
        currentUrl = fallback;
        showLoadingState();
        startLoadTimeoutTimer();
        webView.loadUrl(fallback);
        return true;
    }

    private void showLoadingState() {
        errorView.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        loadingText.setVisibility(View.VISIBLE);
        countdownText.setVisibility(View.VISIBLE);
        countdownText.setText("最大加载时间: 15秒...");
        webView.setVisibility(View.INVISIBLE);
    }

    private void startLoadTimeoutTimer() {
        timeoutHandler.removeCallbacksAndMessages(null);
        timeoutHandler.postDelayed(() -> {
            if (loadingOverlay.getVisibility() == View.VISIBLE) {
                if (tryFallback()) {
                    return;
                }
                showError("加载超时", "页面加载时间过长，请检查网络连接");
            }
        }, LOAD_TIMEOUT);
    }

    private void showContent() {
        loadingOverlay.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        timeoutHandler.removeCallbacksAndMessages(null);
        cancelRetryTimer();
    }

    private void showError(String title, String message) {
        timeoutHandler.removeCallbacksAndMessages(null);
        progressBar.setVisibility(View.GONE);
        loadingText.setVisibility(View.GONE);
        countdownText.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        errorTitle.setText(title);
        errorMessage.setText(message);
        startRetryCountdown();
    }

    private void hideErrorView() {
        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        loadingText.setVisibility(View.VISIBLE);
        countdownText.setVisibility(View.VISIBLE);
    }

    private void startRetryCountdown() {
        cancelRetryTimer();
        retryTimer = new CountDownTimer(RETRY_DELAY, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                retryCountdown.setText((millisUntilFinished / 1000) + "秒后自动重试");
            }

            @Override
            public void onFinish() {
                hideErrorView();
                if (currentUrl != null) {
                    loadUrl(currentUrl);
                }
            }
        }.start();
    }

    private void cancelRetryTimer() {
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }
    }

    private class CustomWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // 换页/重载：录音结果不能打到新页面上，进行中的录音直接取消
            if (voiceBridge != null) {
                voiceBridge.onPageChanged();
            }
            loadingText.setText("正在加载: " + getDomainFromUrl(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            flushCookies();
            showContent();
        }

        // ===== 导航/重定向白名单 =====
        // 白名单内地址：WebView 自己加载；
        // 外部 http(s) 链接：按 open_external_links 交给系统浏览器或直接拦截；
        // 其他协议（tel/mailto/intent 等）：一律拦截，不拉起外部应用。
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        // ===== 子资源兜底：非白名单 http(s) 请求返回 403，防止资源跑出去 =====
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (!BuildConfig.AGENT_BLOCK_EXTERNAL_SUBRESOURCES) {
                return null;
            }
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && !UrlPolicy.isAllowed(uri.toString())) {
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
                        new HashMap<>(), new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) {
                handleNetworkError(
                        error.getDescription() == null ? "" : error.getDescription().toString(),
                        error.getErrorCode());
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                                    String failingUrl) {
            // API < 23 只有这个旧回调
            if (failingUrl != null && failingUrl.equals(view.getUrl())) {
                handleNetworkError(description, errorCode);
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (!request.isForMainFrame()) {
                return;
            }
            int code = errorResponse.getStatusCode();
            if (code >= 500 && tryFallback()) {
                return;
            }
            handleNetworkError("HTTP错误: " + code, code);
        }

        private void handleNetworkError(String description, int errorCode) {
            if (tryFallback()) {
                return;
            }
            String title = "网络连接问题";
            String message = "无法加载内容，请检查网络连接\n错误代码: " + errorCode;
            if (errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                title = "安全证书问题";
                message = "网站安全证书不受信任\n" + description;
            }
            showError(title, message);
        }
    }

    private String getDomainFromUrl(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null ? host : "目标网站";
        } catch (Exception e) {
            return "目标网站";
        }
    }

    // 设置状态栏颜色，并按背景亮度自动选择深色/浅色文字与图标
    private void applyStatusBarColor() {
        int color = ContextCompat.getColor(this, R.color.status_bar);
        getWindow().setStatusBarColor(color);
        double luminance =
                (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 255000.0;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(luminance > 0.5);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            showExitDialog();
        }
    }

    // 退出前菜单：可改服务器地址、清缓存、退出
    private void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(new CharSequence[]{"服务器设置", "清空缓存", "退出应用"}, (dialog, which) -> {
                    if (which == 0) {
                        startActivityForResult(
                                new Intent(this, ServerSetupActivity.class), REQ_SETUP);
                    } else if (which == 1) {
                        clearWebCache();
                    } else {
                        finish();
                    }
                })
                .show();
    }

    // 清空 WebView 各类缓存，保留 Cookie 以免重新登录
    private void clearWebCache() {
        new Thread(() -> {
            WebStorage.getInstance().deleteAllData();
            runOnUiThread(() -> {
                webView.clearCache(true);
                webView.clearHistory();
                if (currentUrl != null) {
                    webView.reload();
                }
                Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // flush 是磁盘 IO，不允许在主线程调用
    private void flushCookies() {
        new Thread(() -> CookieManager.getInstance().flush()).start();
    }

    @Override
    protected void onPause() {
        // 切后台取消录音：Android 9+ 后台取不到麦克风，录出来也是静音/半截
        if (voiceBridge != null) {
            voiceBridge.onPause();
        }
        flushCookies();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (voiceBridge != null) {
            voiceBridge.onDestroy();
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        cancelRetryTimer();
        timeoutHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
