package com.zglinus.wxcallrec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自动录音前台服务。
 *
 * 三态状态机，两个信号源：
 *
 *   IDLE ──来电通知──▶ PREHEAT ──确认──▶ RECORDING ──离开通话 3s──▶ 收尾
 *            │                              ▲
 *            └──拒接/超时（丢缓冲）──────────┘
 *
 * 确认条件（任一）：
 *   a) MODE 变成 IN_COMMUNICATION / IN_CALL
 *   b) 出现「语音通话中」通知
 *
 * 为什么要预热：实测接听比 MODE 早 2.68 秒，「语音通话中」通知又可能晚 5.4 秒。
 * 来电就打开麦克风进环形缓冲（10 秒，不落盘），确认后把这段一起补进去，
 * 才真的不漏开头那句。
 */
public class RecordService extends Service implements NotifyListener.Hook {

    public static final String ACTION_START = "com.zglinus.wxcallrec.START";
    public static final String ACTION_STOP = "com.zglinus.wxcallrec.STOP";
    public static final String ACTION_PAUSE = "com.zglinus.wxcallrec.PAUSE";

    public static volatile boolean running;
    public static volatile String lastUri;
    public static volatile String lastNote = "未启动";

    private static final String CH = "callrec";
    private static final int NOTIF = 9;
    private static final int REQ_OPEN = 0;
    private static final int REQ_STOP = 1;
    private static final int REQ_PAUSE = 2;

    private static final int IDLE = 0;
    private static final int PREHEAT = 1;
    private static final int RECORDING = 2;

    private static final long CONFIRM_TIMEOUT_MS = 6000;   // 通知移除后多久没确认 → 判拒接
    private static final long PREHEAT_MAX_MS = 20000;
    private static final long END_DEBOUNCE_MS = 3000;

    private AudioManager am;
    private File workDir;
    private Thread poller;
    private volatile boolean alive;

    private int state = IDLE;
    private String nickname = "";
    private long incomingAt;
    private long goneAt;
    private long lastInCallAt;

    private Recorder recorder;
    private final List<File> segments = new ArrayList<>();
    private boolean paused;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Crash.install(this);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        workDir = new File(getCacheDir(), "calls");
        NotifyListener.hook = this;
    }

    @Override
    public void onDestroy() {
        if (NotifyListener.hook == this) NotifyListener.hook = null;
        shutdown();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF, build("监听中 · 等通话"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        if (ACTION_PAUSE.equals(action)) {
            synchronized (this) {
                paused = !paused;
                if (paused && state == PREHEAT) doCancel("已暂停");
            }
            refresh(paused ? "已暂停 · 不再响应通话" : "监听中 · 等通话");
            return START_STICKY;
        }

        if (!alive) {
            alive = true;
            running = true;
            poller = new Thread(this::pollLoop, "call-state");
            poller.start();
            EventLog.add("SVC", "服务启动，通知回调已挂钩");
        }
        return START_STICKY;
    }

    private void shutdown() {
        alive = false;
        running = false;
        Thread p = poller;
        poller = null;
        if (p != null) {
            try { p.join(1500); } catch (InterruptedException ignored) {}
        }
        doCancel("服务停止");
        lastNote = "已停止";
    }

    // ---------- 通知钩子（在通知监听线程上调用） ----------

    @Override
    public void onIncoming(String nick) {
        synchronized (this) {
            if (paused || state != IDLE) return;
            nickname = nick;
            incomingAt = System.currentTimeMillis();
            goneAt = 0;
            prepare();
        }
    }

    @Override
    public void onInCall(String nick) {
        synchronized (this) {
            if (!nick.isEmpty()) nickname = nick;
            if (state == RECORDING) return;
            if (state == IDLE) {
                if (paused) return;
                // 极端情况：没收到来电通知但已经在通话里
                incomingAt = System.currentTimeMillis();
                goneAt = 0;
                prepare();
            }
            if (state == PREHEAT) {
                startRecording();
            }
        }
    }

    @Override
    public void onGone() {
        synchronized (this) {
            goneAt = System.currentTimeMillis();
        }
    }

    // ---------- 状态迁移 ----------

    /** 来电只登记，不开麦克风：这时候开的声音流，通话一建立就会被降级静音。 */
    private void prepare() {
        segments.clear();
        state = PREHEAT;
        incomingAt = System.currentTimeMillis();
        EventLog.add("SVC", "来电 · " + (nickname.isEmpty() ? "未知" : nickname));
        refresh("来电 · " + (nickname.isEmpty() ? "未知" : nickname) + " · 等待接通");
    }

    /** 通话已建立，此刻才创建 AudioRecord —— 与探针同一时序。 */
    private void startRecording() {
        if (recorder != null) return;
        recorder = new Recorder(workDir, new Recorder.Listener() {
            @Override
            public void onSegment(File file, int count, long totalMs) {
                synchronized (RecordService.this) {
                    segments.add(file);
                }
                lastNote = "已落盘 " + count + " 段";
                refresh(String.format(Locale.US, "录音中 · %s · %d:%02d · %d 段",
                        nickname, totalMs / 60000, (totalMs / 1000) % 60, count));
            }

            @Override
            public void onSilent(long silent) {
                refresh("⚠ 源被静音：连续 " + (silent / 1000) + " 秒全零");
            }

            @Override
            public void onError(String msg) {
                lastNote = "录音出错: " + msg;
                Log.w("callrec", "recorder error: " + msg);
                refresh("⚠ 录音出错 · " + msg);
            }
        });

        if (!recorder.start()) {
            recorder = null;
            state = IDLE;
            lastNote = "开不了麦克风（见通知）";
            return;
        }
        state = RECORDING;
        lastInCallAt = System.currentTimeMillis();
        EventLog.add("SVC", "开录 · " + (nickname.isEmpty() ? "未知" : nickname));
        refresh("录音中 · " + (nickname.isEmpty() ? "未知" : nickname));
    }

    private void doCancel(String why) {
        if (state == IDLE && recorder == null) return;
        Recorder r = recorder;
        recorder = null;
        if (r != null) r.stop(false);
        synchronized (this) {
            segments.clear();
            state = IDLE;
            nickname = "";
        }
        EventLog.add("SVC", "取消预热 · " + why);
        refresh("监听中 · 等通话");
    }

    private void doFinish() {
        Recorder r = recorder;
        recorder = null;
        state = IDLE;
        if (r == null) return;
        r.stop(true);

        List<File> segs;
        synchronized (this) {
            segs = new ArrayList<>(segments);
            segments.clear();
        }
        long ms = r.totalMs();
        String who = nickname.isEmpty() ? "未知" : nickname;
        nickname = "";

        if (segs.isEmpty()) {
            EventLog.add("SVC", "结束但无数据");
            refresh("监听中 · 等通话");
            return;
        }

        String name = "call_" + safe(who) + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + String.format(Locale.US, "_%dm%02ds.wav", ms / 60000, (ms / 1000) % 60);
        try {
            Uri uri = CallStore.merge(this, segs, Recorder.RATE, name);
            lastUri = uri.toString();
            for (File f : segs) f.delete();
            lastNote = "已保存 " + name;
            EventLog.add("SVC", "已保存 " + name + " (" + (ms / 1000) + "s)");
            notifySaved(name, ms);
        } catch (Exception e) {
            lastNote = "合并失败，分段保留: " + e.getMessage();
            EventLog.add("SVC", "合并失败: " + e.getMessage());
            refresh("⚠ 合并失败，分段留在 cache/calls (" + segs.size() + " 段)");
            Log.w("callrec", "merge failed", e);
            return;
        }
        refresh("已保存 · " + name);
    }

    private static String safe(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 14; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private void pollLoop() {
        while (alive) {
            int mode = am.getMode();
            boolean inCall = mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_IN_CALL;
            long now = System.currentTimeMillis();

            int action = 0;      // 1=begin 2=cancel 3=finish
            String why = null;

            synchronized (this) {
                if (inCall) lastInCallAt = now;

                if (state == PREHEAT) {
                    if (inCall) {
                        action = 1;
                    } else if (goneAt > 0 && now - goneAt > CONFIRM_TIMEOUT_MS) {
                        action = 2;
                        why = "通知移除后 " + (CONFIRM_TIMEOUT_MS / 1000) + " 秒未确认（拒接/未接）";
                    } else if (now - incomingAt > PREHEAT_MAX_MS) {
                        action = 2;
                        why = "预热超时 " + (PREHEAT_MAX_MS / 1000) + " 秒";
                    }
                } else if (state == RECORDING) {
                    if (!inCall && now - lastInCallAt > END_DEBOUNCE_MS) {
                        action = 3;
                    } else if (now % 3000 < 520) {
                        long ms = recorder != null ? recorder.totalMs() : 0;
                        Boolean muted = recorder != null ? recorder.silenced() : null;
                        refresh(String.format(Locale.US, "录音中 · %s · %d:%02d · 静音=%s",
                                nickname.isEmpty() ? "未知" : nickname,
                                ms / 60000, (ms / 1000) % 60,
                                muted == null ? "未知" : (muted ? "是" : "否")));
                    }
                }
            }

            if (action == 1) startRecording();
            else if (action == 2) doCancel(why);
            else if (action == 3) doFinish();

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ---------- 通知 ----------

    private void refresh(String text) {
        Notification n = build(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF, n);
    }

    private void notifySaved(String name, long ms) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF, base()
                .setContentTitle("已保存 · " + String.format(Locale.US, "%d:%02d",
                        ms / 60000, (ms / 1000) % 60))
                .setContentText(name)
                .setOngoing(true)
                .build());
    }

    private Notification build(String text) {
        return base().setContentTitle("通话录音").setContentText(text).build();
    }

    private Notification.Builder base() {
        ensureChannel();
        PendingIntent open = PendingIntent.getActivity(this, REQ_OPEN,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, REQ_STOP,
                new Intent(this, RecordService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, REQ_PAUSE,
                new Intent(this, RecordService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "暂停/继续", pause).build())
                .addAction(new Notification.Action.Builder(null, "停止", stop).build());
    }

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) != null) return;
        NotificationChannel c = new NotificationChannel(CH, "通话录音", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("自动录音状态与告警");
        nm.createNotificationChannel(c);
    }
}
