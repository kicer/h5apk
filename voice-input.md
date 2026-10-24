# 语音输入：客户端录音契约

链路是「客户端录音 → `POST /api/asr/transcribe` → 文字插入输入框」。服务端只收音频字节，
不关心是谁录的。`public/js/voice.js` 按能力自己选录音方式：

| 页面环境 | 录音方式 | 说明 |
|---|---|---|
| Android 套壳 App（WebView） | **原生录音 + JS 桥** | WebView 没有 Web Speech API；http 页面也允许 `getUserMedia` 的替代路径 |
| 浏览器 / PWA（HTTPS） | `MediaRecorder` | 标准 `getUserMedia`，不用改壳 |
| 纯浏览器（`http://<IP>`） | 不可用 | 非安全上下文，浏览器拒绝开麦 |

**按钮什么时候显示**（`voice.js` 判定，两个条件都要满足）：

1. 有录音能力：`window.AgentNative.startVoice` 存在（原生桥），**或** `isSecureContext && getUserMedia && MediaRecorder`；
2. 服务端接线：`GET /api/asr/status` 返回 `enabled !== false`。

所以**套壳 App 里即使页面是 `http://`，只要桥注入成功，按钮照样显示**；「按钮不显示」
只发生在「既没有桥、又不是安全上下文」的纯浏览器里。两条都没有时不做灰按钮。

---

## 一、桥的契约

### 1. 注入时机

必须在 **`loadUrl()` 之前**注册，不要在 `onPageFinished` 里用 `evaluateJavascript` 注入：

```java
webView.setWebViewClient(/* ... */);
webView.addJavascriptInterface(new VoiceBridge(activity, webView), "AgentNative");
webView.loadUrl(startUrl);
```

`addJavascriptInterface` 的绑定对所有后续导航生效；在 `onPageFinished` 里再注入，首屏那次
加载会拿不到 `window.AgentNative`（页面脚本已经跑完了）。

### 2. 方法（壳实现）

```js
window.AgentNative = {
  startVoice(),   // 开始录音；失败/拒绝通过 onNativeVoiceError 回报
  stopVoice(),    // 结束录音；结果通过 onNativeVoiceResult 回传
  cancelVoice()   // 放弃本次录音（无回调；页面自己已经把状态复位了）
};
```

**线程模型**：这三个方法都跑在 WebView 的 JavaBridge 子线程上，**不是主线程**。
`MediaRecorder` 的所有调用必须收敛到主线程：方法入口先 `mainHandler.post(...)`，
读临时文件 + Base64 才放回子线程。

### 3. 回调（页面定义，壳调用）

```js
window.onNativeVoiceResult(base64, mime)   // mime 例：'audio/mp4'
window.onNativeVoiceError(code, message)   // 见下方错误码表
window.onNativeVoiceState('recording')     // 可选：真正开始录时调一次
```

- `onNativeVoiceState('recording')`：**建议实现**。页面在点击时就把按钮切到「录音中」，
  而首次授权弹窗可能吃掉 1~5 秒；收到这个回调后，页面的 60 秒上限才从「真正开始录」重新计时。
  不实现也不影响使用（继续按点击时刻计时，最多少录几秒）。
- 所有回调都必须在**主线程**调 `webView.evaluateJavascript`。
- 字符串一律用 `JSONObject.quote(...)` 拼，不要手写 `'` 转义——异常 message 里可能有
  `\`、换行、U+2028，会把整段脚本打断。

### 4. 状态机与错误码

壳侧状态：`IDLE → (WAITING_PERMISSION) → RECORDING → ENCODING → IDLE`。
`startVoice` 只在 `IDLE` 生效，`stopVoice` 只在 `RECORDING` 生效（其余静默忽略）。

| code | 触发时机 | 页面表现 |
|---|---|---|
| `no_permission` | 用户拒绝 / 取消授权弹窗 | toast「需要麦克风权限」 |
| `start_failed` | `prepare()` / `start()` 抛异常（含回退 MIC 后仍失败） | toast |
| `too_short` | `stop()` 抛异常（录音 < ~1s） | toast「没有录到声音」 |
| `read_failed` | 读临时文件失败 | toast |
| `cancelled` | 生命周期打断（切后台 / 换页 / 页面主动 cancel） | **静默复位，不弹提示** |
| 其他未知 code | — | toast 里显示 `message`，没有就显示 code |

**取消语义**：页面在 `stopVoice()` 之后会一直等结果（直到点击后 90 秒的硬超时），
所以壳在生命周期里取消录音时**必须**回调 `onNativeVoiceError('cancelled','')`，
否则按钮会卡在「录音中」。`cancelVoice()` 是页面主动发起的，壳不需要回报。

### 5. 体积与时序

- 16kHz / 32kbps / 单声道 AAC：60 秒 ≈ **240KB**，Base64 后 ≈ **320KB**。
- `evaluateJavascript` 走 Binder，单次约 1MB 上限；320KB 安全，但**不要再延长单次时长**。
  真要 2 分钟以上，改成按段回传或本地 HTTP 回传。
- 页面侧硬超时：点击后 120 秒没有结果就强制复位（防止壳异常时按钮永久卡死）。

---

## 二、Java 参考骨架（纯 Java / minSdk 21）

只演示契约要求的部分；`UrlPolicy`、埋点、通知等按各自工程接。

```java
// AndroidManifest.xml
//   <uses-permission android:name="android.permission.RECORD_AUDIO" />

public final class VoiceBridge {
    private static final int REQ_MIC = 1001;
    private static final long MAX_MS = 60_000;

    private enum State { IDLE, WAITING_PERMISSION, RECORDING, ENCODING }

    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopOnMain;

    private State state = State.IDLE;
    private MediaRecorder recorder;
    private File file;

    public VoiceBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    // ---- JS 入口：JavaBridge 线程 → 主线程 ----
    @JavascriptInterface public void startVoice()  { main.post(this::startOnMain); }
    @JavascriptInterface public void stopVoice()   { main.post(this::stopOnMain); }
    @JavascriptInterface public void cancelVoice() { main.post(() -> cancelOnMain(true)); }

    // ---- 生命周期：由 Activity / WebViewClient 调用 ----
    // 注意：只取消真正在录/在编码的状态。授权弹窗期间某些 ROM 会走 onPause，
    // 那里如果一并取消，首次授权的两段式流程就断了。
    public void onPause() {
        main.post(() -> {
            if (state == State.RECORDING || state == State.ENCODING) cancelOnMain(true);
        });
    }
    public void onDestroy()       { main.post(() -> cancelOnMain(false)); }
    public void onPageChanged()   { main.post(() -> cancelOnMain(true)); }

    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_MIC) return;
        main.post(() -> {
            if (state != State.WAITING_PERMISSION) return;
            state = State.IDLE;
            boolean ok = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) { fail("no_permission", "需要麦克风权限"); return; }
            beginRecording(); // 授权后直接开录（体验优先，不要求用户再点一次）
        });
    }

    private void startOnMain() {
        if (state != State.IDLE) return;
        // 第三方 CDN 脚本同样能调这个桥：只允许自家页面 + 前台时录音
        if (!UrlPolicy.isAllowed(webView.getUrl()) || !activity.hasWindowFocus()) {
            fail("forbidden", "当前页面不允许录音");
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            state = State.WAITING_PERMISSION;
            ActivityCompat.requestPermissions(
                    activity, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        beginRecording();
    }

    @SuppressLint("NewApi")
    private void beginRecording() {
        // VOICE_RECOGNITION 关掉 AGC/降噪，对 ASR 更友好；个别机型不支持则回退 MIC
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION,
                         MediaRecorder.AudioSource.MIC};
        for (int source : sources) {
            try {
                File f = File.createTempFile("voice", ".m4a", activity.getCacheDir());
                MediaRecorder r = Build.VERSION.SDK_INT >= 31
                        ? new MediaRecorder(activity)   // 无参构造在 API 31 起废弃
                        : new MediaRecorder();
                r.setAudioSource(source);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioSamplingRate(16000);
                r.setAudioEncodingBitRate(32000);
                r.setAudioChannels(1);
                r.setOutputFile(f.getAbsolutePath());
                r.prepare();
                r.start();

                recorder = r;
                file = f;
                state = State.RECORDING;
                main.postDelayed(autoStop, MAX_MS);
                // 让页面的 60s 从“真的开始录”算（授权弹窗的时间不算）
                js("window.onNativeVoiceState && window.onNativeVoiceState('recording')");
                // 建议再加一个原生提示（Toast「正在录音…」）：页面自己也可以被第三方脚本调用
                return;
            } catch (Exception e) {
                releaseQuietly();
            }
        }
        state = State.IDLE;
        fail("start_failed", "无法开始录音");
    }

    private void stopOnMain() {
        if (state != State.RECORDING) return;
        main.removeCallbacks(autoStop);
        state = State.ENCODING;

        MediaRecorder r = recorder;
        File f = file;
        recorder = null;
        file = null;

        boolean stopped = true;
        try { r.stop(); } catch (RuntimeException e) { stopped = false; } // 太短会抛
        try { r.release(); } catch (Exception ignored) { }

        if (!stopped) {
            delete(f);
            state = State.IDLE;
            fail("too_short", "没有录到声音");
            return;
        }
        new Thread(() -> {
            try {
                byte[] bytes = readAll(f);
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                delete(f);
                main.post(() -> {
                    state = State.IDLE;
                    js("window.onNativeVoiceResult(" + JSONObject.quote(b64) + ",'audio/mp4')");
                });
            } catch (Exception e) {
                delete(f);
                main.post(() -> { state = State.IDLE; fail("read_failed", "读取录音失败"); });
            }
        }, "voice-encode").start();
    }

    /** notify=true：页面在等结果，必须回 cancelled 让它复位；onDestroy 时页面已走，不必回。 */
    private void cancelOnMain(boolean notify) {
        main.removeCallbacks(autoStop);
        boolean wasActive = state != State.IDLE;
        releaseQuietly();
        delete(file);
        file = null;
        state = State.IDLE;
        if (notify && wasActive) js("window.onNativeVoiceError('cancelled','')");
    }

    private void releaseQuietly() {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception ignored) { }
        try { recorder.release(); } catch (Exception ignored) { }
        recorder = null;
    }

    private void fail(String code, String message) {
        js("window.onNativeVoiceError(" + JSONObject.quote(code) + ","
                + JSONObject.quote(message) + ")");
    }

    private void js(String script) {
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void delete(File f) {
        if (f != null) f.delete();
    }
}
```

接线：

```java
// setupWebView() 里，loadUrl 之前
webView.addJavascriptInterface(new VoiceBridge(this, webView), "AgentNative");

@Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
    super.onRequestPermissionsResult(code, p, r);
    bridge.onRequestPermissionsResult(code, r);
}
@Override protected void onPause()     { bridge.onPause(); super.onPause(); }
@Override protected void onDestroy()   { bridge.onDestroy(); super.onDestroy(); }
// WebViewClient.onPageStarted：URL 变了就 bridge.onPageChanged()
```

---

## 三、壳侧必须同步的清单

| 项 | 内容 |
|---|---|
| `AndroidManifest.xml` | `<uses-permission android:name="android.permission.RECORD_AUDIO" />` |
| `proguard-rules.pro` | 保留桥：`-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }`，并删掉「本项目未使用 addJavascriptInterface」的注释 |
| README 安全表 | 从「不使用 JS 桥」改为「仅暴露语音桥：三个无参方法，无文件/存储读取能力」 |
| README 权限行 | 「只有 INTERNET」→「INTERNET + RECORD_AUDIO」 |
| 桥的防护 | `UrlPolicy.isAllowed(webView.getUrl())` + `activity.hasWindowFocus()` + 单实例状态机；建议再加一个原生 Toast 提示录音已开始（外部 CDN 脚本与页面同源，能调桥，但用户至少看得见） |
| 页面导航 | `onPageStarted` 且 URL 变化时取消，避免 base64 打到新页面的回调上 |

> 触摸时间戳门槛（最近 N 秒内有触摸才允许录音）能进一步防静默录音，但会让无障碍操作不可用，
> 不建议默认开。

---

## 四、iOS（以后若有 WKWebView 壳）

契约相同，换注入方式：

```swift
// 注入：WKUserScript(source: "window.AgentNative = {...}", injectionTime: .atDocumentStart, ...)
// 录音：AVAudioRecorder（16kHz / 单声道 / m4a）
// 回传：webView.evaluateJavaScript("window.onNativeVoiceResult('\(b64)','audio/mp4')")
```

`Info.plist` 需要 `NSMicrophoneUsageDescription`。

## 五、服务端接口

```http
POST /api/asr/transcribe
Content-Type: audio/mp4        # 或 audio/webm / audio/wav / application/octet-stream
Cookie: <登录态>                # 与页面同一份 HttpOnly cookie

<音频二进制>
```

```json
{ "text": "打开今天的日报", "ms": 842, "model": "glm-asr" }
```

- 上限 20MB，超时 120s；识别失败返回 `{"error": "..."}`（前端直接 toast 出来）。
- `GET /api/asr/status` → `{"enabled": true}`：`ASR_URL` 没接线时为 `false`。

## 六、为什么不用浏览器的 Web Speech API

- Android WebView 没有实现 `SpeechRecognition`；iOS WKWebView 同样不可用（只有 Safari 有）。
- 桌面 Chrome 的识别走 Google 服务，国内基本报 `network`。
- 三端行为不一致、错误码各异，最后还是要留一条服务端通路——不如只留一条。
