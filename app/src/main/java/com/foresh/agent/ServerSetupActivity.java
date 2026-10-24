package com.foresh.agent;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 服务器地址设置页。
 *
 * <p>首次启动时强制出现一次（可留空直接走默认地址），之后从主界面的「返回键菜单 →
 * 服务器设置」进入。手动配置只接受 origin（IP/域名 + 可选端口），不接受路径和参数。
 */
public class ServerSetupActivity extends AppCompatActivity {

    /** true = 首次启动（不可取消，返回键等同「使用默认地址」）。 */
    public static final String EXTRA_FIRST_RUN = "first_run";

    private EditText input;
    private TextView error;
    private boolean firstRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 进程被系统回收后可能直接恢复设置页，这里也初始化一次（幂等）
        ServerConfig.init(this);
        setContentView(R.layout.activity_server_setup);
        applyStatusBarColor();

        firstRun = getIntent().getBooleanExtra(EXTRA_FIRST_RUN, false);

        input = findViewById(R.id.server_input);
        error = findViewById(R.id.server_error);

        String saved = ServerConfig.savedOrigin();
        if (!saved.isEmpty()) {
            input.setText(saved);
            input.setSelection(saved.length());
        }

        TextView hint = findViewById(R.id.server_hint);
        hint.setText("只填 IP 或域名，端口默认 " + ServerConfig.DEFAULT_HTTP_PORT + "。\n"
                + "例：192.168.1.10 → http://192.168.1.10:" + ServerConfig.DEFAULT_HTTP_PORT + "\n"
                + "留空则使用默认地址：" + BuildConfig.AGENT_FIXED_URL);

        Button confirm = findViewById(R.id.server_confirm);
        confirm.setText(firstRun ? "开始使用" : "保存");
        confirm.setOnClickListener(v -> applyInput());

        Button useDefault = findViewById(R.id.server_default);
        useDefault.setText(firstRun ? "跳过，使用默认地址" : "恢复默认地址");
        useDefault.setOnClickListener(v -> applyDefault());

        Button cancel = findViewById(R.id.server_cancel);
        cancel.setVisibility(firstRun ? View.GONE : View.VISIBLE);
        cancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        input.requestFocus();
        input.post(() -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (firstRun) {
            // 首次启动没有可返回的页面，按默认地址继续
            applyDefault();
            return;
        }
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private void applyInput() {
        String normalized;
        try {
            normalized = ServerConfig.normalizeUserInput(input.getText().toString());
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
            error.setVisibility(View.VISIBLE);
            return;
        }
        error.setVisibility(View.GONE);
        if (normalized == null) {
            // 留空 = 使用默认地址
            applyDefault();
            return;
        }
        ServerConfig.saveOrigin(normalized);
        finishWithResult();
    }

    private void applyDefault() {
        ServerConfig.clearOverride();
        finishWithResult();
    }

    private void finishWithResult() {
        ServerConfig.setSetupDone();
        hideKeyboard();
        setResult(Activity.RESULT_OK);
        finish();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) {
            return;
        }
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void applyStatusBarColor() {
        int color = ContextCompat.getColor(this, R.color.status_bar);
        getWindow().setStatusBarColor(color);
        double luminance =
                (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 255000.0;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(luminance > 0.5);
    }
}
