package com.foresh.agent;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * 语音输入的原生录音桥，由 MainActivity 注入为 {@code window.AgentNative}。
 *
 * <p>契约见仓库根目录 {@code voice-input.md}：页面调用 startVoice / stopVoice / cancelVoice，
 * 壳通过 {@code window.onNativeVoiceState('recording')}、{@code window.onNativeVoiceAmp(amp)}、
 * {@code window.onNativeVoiceResult(base64, mime)}、{@code window.onNativeVoiceError(code, message)}
 * 回调。
 *
 * <p>线程：三个 {@link JavascriptInterface} 入口跑在 WebView 的 JavaBridge 子线程，
 * MediaRecorder 的所有操作都收敛到主线程；只有读临时文件 + Base64 编码放在子线程。
 * 状态字段只在主线程读写。
 *
 * <p>安全：第三方脚本与页面同源，同样能调这个桥，所以真正开录前要过
 * {@link UrlPolicy} 白名单 + App 在前台；单实例状态机保证不会叠加录音。
 */
public final class VoiceBridge {

    private static final int REQ_MIC = 1001;
    private static final long MAX_RECORD_MS = 60_000L;
    private static final String MIME = "audio/mp4";

    private enum State { IDLE, WAITING_PERMISSION, RECORDING, ENCODING }

    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopOnMain;

    // 实时电平：每 100ms 读一次 getMaxAmplitude()，交给页面画声波
    private static final long LEVEL_INTERVAL_MS = 100L;
    private final Runnable levelTick = this::tickLevel;

    private State state = State.IDLE;
    private MediaRecorder recorder;
    private File file;
    // 每次录音递增；取消后旧编码线程的结果直接丢弃，不会回传到新会话
    private int session;
    private boolean destroyed;
    // 授权弹窗期间用户已经松手（stopVoice 早于权限结果）：授权后不要再凭空开录
    private boolean stopWhileWaiting;

    public VoiceBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    // ===== JS 入口：JavaBridge 线程 → 主线程 =====

    @JavascriptInterface
    public void startVoice() {
        main.post(this::startOnMain);
    }

    @JavascriptInterface
    public void stopVoice() {
        main.post(this::stopOnMain);
    }

    /** 页面主动放弃：页面自己已复位，壳不回任何回调。 */
    @JavascriptInterface
    public void cancelVoice() {
        main.post(() -> cancelOnMain(false));
    }

    // ===== 生命周期：由 Activity / WebViewClient 调用 =====

    /**
     * 切后台：只打断真正在录/在编码的会话。
     * 授权弹窗期间部分 ROM 也会走 onPause，那里不能取消，否则首次授权的两段式流程会断。
     */
    public void onPause() {
        main.post(() -> {
            if (state == State.RECORDING || state == State.ENCODING) {
                cancelOnMain(true);
            }
        });
    }

    /** 换页：base64 不能打到新页面的回调上，必须取消；页面还在等结果所以要回报。 */
    public void onPageChanged() {
        main.post(() -> {
            if (state == State.RECORDING || state == State.ENCODING) {
                cancelOnMain(true);
            }
        });
    }

    /** Activity 销毁：静默清理，页面已经不在了，不需要回报。 */
    public void onDestroy() {
        main.post(() -> {
            destroyed = true;
            cancelOnMain(false);
        });
    }

    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_MIC) {
            return;
        }
        main.post(() -> {
            if (state != State.WAITING_PERMISSION) {
                return;
            }
            state = State.IDLE;
            boolean stopRequested = stopWhileWaiting;
            stopWhileWaiting = false;
            // 用户点返回/点外部取消时 grantResults 为空
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                fail("no_permission", "需要麦克风权限");
                return;
            }
            if (stopRequested) {
                // 按住说话时用户得松手去点「允许」，这段录音已经不存在了，
                // 绝不能授权后凭空开一段没人停的麦
                fail("too_short", "说话时间太短，请按住再说");
                return;
            }
            // 还在按住（或手指又落下来）：授权后直接开录，不要求用户再点一次
            beginRecording();
        });
    }

    // ===== 主线程逻辑 =====

    private void startOnMain() {
        if (destroyed || state != State.IDLE) {
            return; // 防连点 / 防并发
        }
        stopWhileWaiting = false;
        // 第三方 CDN 脚本同样能调这个桥：只允许自家页面 + App 在前台时录音
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

    /**
     * 依次尝试：VOICE_RECOGNITION(16k) → MIC(16k) → MIC(设备默认参数)。
     * 前两种关掉 AGC/降噪、体积小、对 ASR 友好；个别机型不支持 16k 时退到默认参数，
     * 由服务端 ffmpeg 归一化。
     */
    @SuppressWarnings("deprecation")
    private void beginRecording() {
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean deviceDefault = attempt == 2;
            int source = attempt == 0
                    ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                    : MediaRecorder.AudioSource.MIC;

            File f = null;
            MediaRecorder r = null;
            try {
                f = File.createTempFile("voice", ".m4a", activity.getCacheDir());
                // 无参构造在 API 31 起废弃
                r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? new MediaRecorder(activity)
                        : new MediaRecorder();
                r.setAudioSource(source);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                if (!deviceDefault) {
                    r.setAudioSamplingRate(16000);
                    r.setAudioEncodingBitRate(32000);
                    r.setAudioChannels(1);
                }
                r.setOutputFile(f.getAbsolutePath());
                r.prepare();
                r.start();
            } catch (Exception e) {
                if (r != null) {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
                delete(f);
                continue;
            }

            recorder = r;
            file = f;
            session++;
            state = State.RECORDING;
            main.postDelayed(autoStop, MAX_RECORD_MS);
            main.postDelayed(levelTick, LEVEL_INTERVAL_MS);
            // 让页面的 60s 从「真的开始录」算，授权弹窗的时间不算
            js("window.onNativeVoiceState && window.onNativeVoiceState('recording')");
            return;
        }

        state = State.IDLE;
        fail("start_failed", "无法开始录音");
    }

    /**
     * 每 100ms 读一次峰值振幅（0~32767）原样交给页面；换算、归一化、平滑全在页面侧做，
     * 壳不做任何处理（不取对数、不 clamp、不格式化）。
     */
    private void tickLevel() {
        if (state != State.RECORDING || recorder == null) {
            return;
        }
        int amp = 0;
        try {
            amp = recorder.getMaxAmplitude(); // 距上次调用以来的峰值，采不到时为 0
        } catch (Exception ignored) {
            // getMaxAmplitude 个别机型会抛，按静音处理
        }
        js("window.onNativeVoiceAmp && window.onNativeVoiceAmp(" + amp + ")");
        main.postDelayed(levelTick, LEVEL_INTERVAL_MS);
    }

    private void stopOnMain() {
        if (state == State.WAITING_PERMISSION) {
            // 按住说话的常见路径：手指抬起去点「允许」时页面已经 stopVoice 了。
            // 记下来，等权限结果回来时不要开始一段没人停的录音。
            stopWhileWaiting = true;
            return;
        }
        if (state != State.RECORDING) {
            return;
        }
        main.removeCallbacks(autoStop);
        main.removeCallbacks(levelTick);
        state = State.ENCODING;

        final int mySession = session;
        MediaRecorder r = recorder;
        File f = file;
        recorder = null;
        file = null;

        boolean stopped = r != null;
        if (r != null) {
            try {
                r.stop(); // 录音过短（< ~1s）会抛 RuntimeException，按「没录到」处理
            } catch (RuntimeException e) {
                stopped = false;
            }
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }

        if (!stopped || f == null) {
            delete(f);
            state = State.IDLE;
            fail("too_short", "说话时间太短，请按住再说");
            return;
        }

        new Thread(() -> {
            byte[] bytes;
            try {
                bytes = readAll(f);
            } catch (Exception e) {
                delete(f);
                main.post(() -> {
                    if (mySession != session) {
                        return; // 编码期间已被取消，结果作废
                    }
                    state = State.IDLE;
                    fail("read_failed", "读取录音失败");
                });
                return;
            }
            delete(f);
            final String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            main.post(() -> {
                if (mySession != session) {
                    return; // 编码期间已被取消，结果作废
                }
                state = State.IDLE;
                js("window.onNativeVoiceResult(" + JSONObject.quote(b64)
                        + "," + JSONObject.quote(MIME) + ")");
            });
        }, "voice-encode").start();
    }

    /**
     * @param notify true = 页面还在等结果（切后台 / 换页），必须回 cancelled 让按钮复位；
     *               页面主动 cancelVoice 和 onDestroy 都传 false。
     */
    private void cancelOnMain(boolean notify) {
        main.removeCallbacks(autoStop);
        main.removeCallbacks(levelTick);
        boolean pageWaiting = state == State.RECORDING || state == State.ENCODING;
        session++; // 让正在编码的旧结果作废
        stopWhileWaiting = false;
        releaseQuietly();
        delete(file);
        file = null;
        state = State.IDLE;
        if (notify && pageWaiting) {
            fail("cancelled", "");
        }
    }

    private void releaseQuietly() {
        if (recorder == null) {
            return;
        }
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
    }

    private void fail(String code, String message) {
        js("window.onNativeVoiceError(" + JSONObject.quote(code)
                + "," + JSONObject.quote(message) + ")");
    }

    /** 主线程调用；WebView 销毁后静默丢弃。 */
    private void js(String script) {
        if (destroyed) {
            return;
        }
        webView.post(() -> {
            if (destroyed) {
                return;
            }
            try {
                webView.evaluateJavascript(script, null);
            } catch (Exception ignored) {
                // WebView 已销毁：丢弃回调
            }
        });
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void delete(File f) {
        if (f != null) {
            f.delete();
        }
    }
}
