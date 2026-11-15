package com.zglinus.wxcallrec;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** 主界面：三项权限齐了才能开自动录音；下半是时间轴。 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;
    private static final int REQ_NOTIF = 2;
    private static final String PREFS = "settings";
    private static final String KEY_AUTO = "auto";
    private static final long TICK_MS = 250L;

    private AudioManager am;
    private Switch swAuto;
    private TextView tvStatus, tvPerm, tvLog;
    private ScrollView logBox;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int rendered;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            update();
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Crash.install(this);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setContentView(buildUi());
        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
        update();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("微信通话录音");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton btnList = new ImageButton(this);
        btnList.setImageResource(R.drawable.ic_player);
        btnList.setBackgroundColor(0x00000000);
        btnList.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnList.setOnClickListener(v -> startActivity(new Intent(this, RecordListActivity.class)));
        head.addView(btnList, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setTextSize(11);
        sub.setTextColor(0xFF9AA0A6);
        sub.setPadding(0, dp(2), 0, dp(12));
        sub.setText("录音源 VOICE_RECOGNITION · 接通后开录 · 30 秒分段落盘");
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = new TextView(this);
        lbl.setText("自动录音");
        lbl.setTextSize(16);
        row.addView(lbl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        swAuto = new Switch(this);
        swAuto.setOnCheckedChangeListener((b, checked) -> setAuto(checked));
        row.addView(swAuto);
        root.addView(row);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTypeface(Typeface.MONOSPACE);
        tvStatus.setPadding(0, dp(8), 0, dp(8));
        root.addView(tvStatus);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("无障碍", v -> openA11y()), weight());
        row2.addView(button("通知", v -> openNotifAccess()), weight());
        row2.addView(button("自检", v -> runSelfTest()), weight());
        root.addView(row2);

        tvPerm = new TextView(this);
        tvPerm.setTextSize(11);
        tvPerm.setTextColor(0xFF9AA0A6);
        tvPerm.setPadding(0, dp(8), 0, dp(6));
        root.addView(tvPerm);

        TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setTextColor(0xFF9AA0A6);
        hint.setPadding(0, 0, 0, dp(8));
        hint.setText("第一次用，三项都要开，缺一不可——\n"
                + "① 麦克风权限\n"
                + "② 通知使用权（没有就只靠音频模式兜底，开头晚 2 秒多）\n"
                + "③ 无障碍「牛马保命神器 · 录音辅助」（没有它，通话中会录成静音）\n"
                + "三项齐了，自动录音开关才能打开。\n"
                + "然后正常接微信语音，挂断后自动存到 Recordings/（写不进会自动退到 Download/通话录音/）。");
        root.addView(hint);

        logBox = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvLog.setBackgroundColor(0x11FFFFFF);
        logBox.addView(tvLog);
        root.addView(logBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView copyright = new TextView(this);
        copyright.setText("© 2026 zglinus · 仅供个人使用");
        copyright.setTextSize(10);
        copyright.setTextColor(0xFF6B7079);
        copyright.setGravity(Gravity.CENTER);
        copyright.setPadding(0, dp(6), 0, dp(10));
        root.addView(copyright, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        return lp;
    }

    private void ensurePermissions() {
        if (!micGranted()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notifGranted() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) {
            return NotifyListener.connected;
        }
    }

    private boolean a11yGranted() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void setAuto(boolean on) {
        if (on && !(micGranted() && notifGranted() && a11yGranted())) {
            swAuto.setChecked(false);
            String miss = missing();
            toast("缺：" + miss);
            EventLog.add("SVC", "拒绝启动自动录音 · 缺 " + miss);
            return;
        }
        prefs().edit().putBoolean(KEY_AUTO, on).apply();
        try {
            Intent svc = new Intent(this, RecordService.class);
            svc.setAction(on ? RecordService.ACTION_START : RecordService.ACTION_STOP);
            if (on) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable t) {
            toast("服务起不来: " + t.getMessage());
        }
        update();
    }

    private String missing() {
        StringBuilder sb = new StringBuilder();
        if (!micGranted()) sb.append("麦克风 ");
        if (!notifGranted()) sb.append("通知 ").append(" ");
        if (!a11yGranted()) sb.append("无障碍 ");
        return sb.toString().trim();
    }

    private void update() {
        boolean auto = prefs().getBoolean(KEY_AUTO, false);
        if (swAuto.isChecked() != auto) swAuto.setChecked(auto);

        boolean mic = micGranted();
        boolean notif = notifGranted();
        boolean a11y = a11yGranted();
        boolean all = mic && notif && a11y;

        if (!all && auto) {
            prefs().edit().putBoolean(KEY_AUTO, false).apply();
            swAuto.setChecked(false);
            try {
                startService(new Intent(this, RecordService.class)
                        .setAction(RecordService.ACTION_STOP));
            } catch (Throwable ignored) {}
            EventLog.add("SVC", "权限不全，自动录音已关闭（缺 " + missing() + "）");
        }

        String uri = RecordService.lastUri;
        tvStatus.setText(String.format(Locale.US,
                "%s %s · Android %s (API %d)%n服务: %s   通知: %s   无障碍: %s%n音频模式: %s%n%s%n%s",
                Build.MANUFACTURER, Build.MODEL,
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                RecordService.running ? "运行中" : "未运行",
                notif ? "已授权" : "未授权",
                a11y ? "已启用" : "未启用",
                modeName(am.getMode()),
                RecordService.lastNote,
                uri == null ? "还没有录音文件" : "最近: " + uri));

        StringBuilder sb = new StringBuilder("权限：麦克风 ");
        sb.append(mic ? "✓" : "✗").append(" · 通知 ").append(notif ? "✓" : "✗")
                .append(" · 无障碍 ").append(a11y ? "✓" : "✗");
        if (all) sb.append("\n三项齐了，可以开自动录音");
        else sb.append("\n还差：").append(missing()).append("，开关点不动");
        tvPerm.setText(sb.toString());

        int size = EventLog.size();
        if (size < rendered) {
            rendered = 0;
            tvLog.setText("");
        }
        List<EventLog.E> list = EventLog.slice(rendered);
        if (list.isEmpty()) return;
        StringBuilder lg = new StringBuilder();
        for (EventLog.E e : list) {
            lg.append(String.format(Locale.US, "%tT.%03d %-6s %s%n",
                    new java.util.Date(e.ts), e.ts % 1000, e.kind, e.text));
        }
        rendered = size;
        tvLog.append(lg);
        logBox.post(() -> logBox.fullScroll(View.FOCUS_DOWN));
    }

    private String modeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            default: return "MODE_" + mode;
        }
    }

    private void openNotifAccess() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Throwable t) {
            toast("打不开设置: " + t.getMessage());
        }
    }

    private void openA11y() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            toast("打不开设置: " + t.getMessage());
        }
    }

    private void runSelfTest() {
        toast("自检 5 秒");
        new Thread(() -> {
            Recorder.SelfCheck c = Recorder.selfTest(5);
            EventLog.add("SELF", String.format(Locale.US, "%s rms=%.0f peak=%d %s",
                    c.verdict(), c.rms, c.peak, c.error));
            ui.post(this::update);
        }, "selftest").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
