package com.foresh.agent;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * 语音输入的原生录音桥，由 MainActivity 注入为 {@code window.AgentNative}。
 *
 * <p>契约见仓库根目录 {@code voice-input.md}：页面调用 startVoice / stopVoice / cancelVoice；
 * 壳用 {@link AudioRecord} 固定 16kHz / 单声道 / 16bit 采集，每约 100ms 通过
 * {@code window.onNativeVoiceChunk(base64Pcm, 16000)} 推一片裸 PCM；
 * 真正开始录音时回调 {@code window.onNativeVoiceState('recording')}，
 * 出错时回调 {@code window.onNativeVoiceError(code, message)}。
 *
 * <p>线程：三个 {@link JavascriptInterface} 入口跑在 WebView 的 JavaBridge 子线程，
 * 统一 post 到主线程；AudioRecord 的 read() 在独立采集线程里阻塞执行。
 * 主线程只调用 stop() 打断阻塞中的 read()，release() 只在采集线程的 finally 里执行，
 * 避免跨线程操作同一个 AudioRecord。状态字段只在主线程读写。
 *
 * <p>安全：第三方脚本与页面同源，同样能调这个桥，所以真正开录前要过
 * {@link UrlPolicy} 白名单 + App 在前台；单实例状态机保证不会叠加录音。
 */
public final class VoiceBridge {

    private static final int REQ_MIC = 1001;

    // 契约固定：16kHz / 单声道 / 16bit 小端裸 PCM
    private static final int RATE = 16000;
    private static final int CHUNK_MS = 100;
    private static final int SAMPLES_PER_CHUNK = RATE * CHUNK_MS / 1000; // 1600
    private static final int CHUNK_BYTES = SAMPLES_PER_CHUNK * 2;         // 3200

    // 页面自己会在 180s 主动 stopVoice()，这里只是兜底
    private static final long MAX_RECORD_MS = 180_000L;

    private enum State { IDLE, WAITING_PERMISSION, RECORDING }

    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopOnMain;

    private State state = State.IDLE;
    private AudioRecord record;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean destroyed;

    // 授权弹窗期间用户已经松手：授权回来后不要凭空开一段没人停的录音
    private boolean stopWhileWaiting;

    public VoiceBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    // ===== JS 入口：JavaBridge 线程 → 主线程 =====

    @JavascriptInterface
    public void startVoice() {
        runOnMain(this::startOnMain);
    }

    /** 结束录音：只停采集，不再编码/回传；已发出的分片由页面自己等结果。 */
    @JavascriptInterface
    public void stopVoice() {
        runOnMain(this::stopOnMain);
    }

    /** 页面主动放弃：页面自己已复位，壳不回任何回调。 */
    @JavascriptInterface
    public void cancelVoice() {
        runOnMain(() -> cancelOnMain(false));
    }

    // ===== 生命周期：由 Activity / WebViewClient 调用 =====

    /**
     * 切后台：只打断真正在录的会话。
     * 授权弹窗期间部分 ROM 也会走 onPause，那里不能取消，否则首次授权的两段式流程会断。
     */
    public void onPause() {
        runOnMain(() -> {
            if (state == State.RECORDING) {
                cancelOnMain(true);
            }
        });
    }

    /** 换页/重载：分片不能打到新页面上，进行中的录音必须取消。 */
    public void onPageChanged() {
        runOnMain(() -> {
            if (state == State.RECORDING) {
                cancelOnMain(true);
            } else if (state == State.WAITING_PERMISSION) {
                // 页面已经换了，授权回来也不该在新页面上凭空开录
                stopWhileWaiting = false;
                state = State.IDLE;
            }
        });
    }

    /** Activity 销毁：静默清理，页面已经不在了，不需要回报。 */
    public void onDestroy() {
        runOnMain(() -> {
            destroyed = true;
            cancelOnMain(false);
        });
    }

    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_MIC) {
            return;
        }
        runOnMain(() -> {
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
            if (stopRequested || destroyed) {
                // 按住说话时用户得松手去点「允许」，这段录音已经不存在了，
                // 绝不能授权后凭空开一段没人停的麦
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

    private void beginRecording() {
        if (destroyed) {
            state = State.IDLE;
            return;
        }

        int minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            // 个别设备不支持 16k：契约要求页面按 16k 估算时长与切段，
            // 不能直接推 44.1k，因此这里直接失败（而不是偷偷改采样率）
            state = State.IDLE;
            fail("start_failed", "设备不支持 16kHz 录音");
            return;
        }

        // VOICE_RECOGNITION 关掉 AGC/降噪，对 ASR 更友好；个别机型不支持则回退 MIC
        final AudioRecord r = createRecord(minBuf);
        if (r == null) {
            state = State.IDLE;
            fail("start_failed", "无法开始录音");
            return;
        }

        record = r;
        running = true;
        state = State.RECORDING;
        main.postDelayed(autoStop, MAX_RECORD_MS);

        worker = new Thread(() -> captureLoop(r), "voice-capture");
        worker.start();
    }

    private AudioRecord createRecord(int minBuf) {
        // 至少留 4 个分片的余量，避免 read() 因缓冲过小频繁返回
        int bufferBytes = Math.max(minBuf * 2, CHUNK_BYTES * 4);
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int source : sources) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 换下一个 source
            }
            if (candidate != null) {
                try {
                    candidate.release();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 采集线程：阻塞 read → 小端字节 → Base64 → 推给页面。
     * 壳不判静音、不切段、不算音量；这些规则只存在页面侧。
     */
    private void captureLoop(AudioRecord r) {
        boolean started = false;
        try {
            if (!running) {
                return;
            }
            r.startRecording();
            if (!running) {
                return; // stop() 已抢先，直接走 finally 释放
            }
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord 未进入录音状态");
            }
            started = true;

            // 让页面的 180s 从「真的开始录」算（授权弹窗的时间不算）
            js("window.onNativeVoiceState && window.onNativeVoiceState('recording')");

            short[] pcm = new short[SAMPLES_PER_CHUNK];
            byte[] bytes = new byte[CHUNK_BYTES];
            while (running) {
                int n = r.read(pcm, 0, pcm.length);
                if (n <= 0) {
                    if (!running) {
                        break; // 主线程 stop() 打断了阻塞中的 read()
                    }
                    throw new IllegalStateException("AudioRecord.read=" + n);
                }
                for (int i = 0; i < n; i++) {
                    bytes[i * 2] = (byte) (pcm[i] & 0xff);
                    bytes[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
                }
                String b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP);
                // JSONObject.quote 统一转义；Base64 本身安全，这里只是统一规矩
                js("window.onNativeVoiceChunk(" + JSONObject.quote(b64) + "," + RATE + ")");
            }
        } catch (Exception e) {
            if (running) {
                running = false;
                final String code = started ? "read_failed" : "start_failed";
                final String detail = e.getMessage() == null
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                final String message = (started ? "录音中断：" : "无法开始录音：") + detail;
                main.post(() -> failCapture(r, code, message));
            }
        } finally {
            running = false;
            // 采集线程是唯一 release() 的地方；主线程的 stop() 只负责打断 read()
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 采集线程异常后的收尾；已被 stop/cancel 接管时不再重复报错。 */
    private void failCapture(AudioRecord r, String code, String message) {
        if (destroyed || record != r) {
            return;
        }
        main.removeCallbacks(autoStop);
        record = null;
        state = State.IDLE;
        fail(code, message);
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
        stopCapture();
        state = State.IDLE;
        // 注意：这里不再回传音频，识别分段已经在录音过程中发完了
    }

    /**
     * @param notify true = 页面还在等收尾（切后台 / 换页），回 cancelled 让页面立刻收遮罩；
     *               页面主动 cancelVoice 和 onDestroy 都传 false。
     */
    private void cancelOnMain(boolean notify) {
        main.removeCallbacks(autoStop);
        boolean wasRecording = state == State.RECORDING;
        stopWhileWaiting = false;
        stopCapture();
        state = State.IDLE;
        if (notify && wasRecording && !destroyed) {
            fail("cancelled", "");
        }
    }

    private void stopCapture() {
        running = false;
        AudioRecord r = record;
        record = null;
        if (r != null) {
            // 打断阻塞中的 read()；stop/release 由采集线程的 finally 完成，
            // 避免跨线程操作同一个 AudioRecord
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
        }
    }

    private void fail(String code, String message) {
        js("window.onNativeVoiceError(" + JSONObject.quote(code)
                + "," + JSONObject.quote(message) + ")");
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            main.post(action);
        }
    }

    /** 任意线程调用；WebView 销毁后静默丢弃。 */
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
}
