package com.zglinus.wxcallrec;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 半屏播放器。
 *   展开：标题 + 能量柱 + 时间/进度/时间 + 六个控制键
 *   收起：只剩一条 [▶ ▬▬●▬▬ 0:12]，播放不中断
 * 点能量区或遮罩收起；点收起条展开；右上 ✕ 才停。
 */
public class PlayerSheet extends LinearLayout {

    public interface Listener {
        void onRequestClose();

        /** 收起/展开变化，外层据此让遮罩让出触摸。 */
        void onCollapsedChanged(boolean collapsed);
    }

    private static final int STEP = 1000;

    private BarsView bars;
    private LinearLayout header, energyBox, rowA, rowB, miniBar;
    private SeekBar seek, seekMini;
    private TextView tvElapsed, tvTotal, tvTitle, tvMiniTime, tvParams;
    private Button btnPlay, btnMiniPlay;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private boolean prepared, userSeeking, collapsed;
    private int durMs;
    private Listener listener;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mp != null && prepared && !userSeeking) {
                try {
                    int pos = mp.getCurrentPosition();
                    if (durMs > 0) {
                        int p = (int) (pos * (long) STEP / durMs);
                        bars.setProgress(pos / (float) durMs);
                        seek.setProgress(p);
                        seekMini.setProgress(p);
                        tvElapsed.setText(fmt(pos));
                        tvMiniTime.setText(fmt(pos));
                    }
                } catch (Throwable ignored) {}
            }
            ui.postDelayed(this, 120L);
        }
    };

    public PlayerSheet(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF14171C);
        bg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        setBackground(bg);
        setPadding(dp(16), dp(8), dp(16), dp(10));

        // ---- 标题行（收起时隐藏）----
        header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tvTitle = new TextView(ctx);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        header.addView(tvTitle, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClose = new Button(ctx);
        btnClose.setText("✕");
        btnClose.setAllCaps(false);
        btnClose.setTextSize(13);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> {
            if (listener != null) listener.onRequestClose();
        });
        header.addView(btnClose, new LayoutParams(dp(40), dp(36)));
        addView(header);

        // ---- 能量区 ----
        energyBox = new LinearLayout(ctx);
        energyBox.setOrientation(VERTICAL);
        bars = new BarsView(ctx);
        energyBox.addView(bars, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bars.setSeekListener((fraction, commit) -> {
            if (durMs <= 0) return;
            int ms = (int) (fraction * durMs);
            tvElapsed.setText(fmt(ms));
            tvMiniTime.setText(fmt(ms));
            seek.setProgress((int) (fraction * STEP));
            seekMini.setProgress((int) (fraction * STEP));
            if (commit && mp != null && prepared) {
                try { mp.seekTo(ms); } catch (Throwable ignored) {}
            }
        });
        addView(energyBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        // 参数区
        tvParams = new TextView(ctx);
        tvParams.setTextSize(10);
        tvParams.setTypeface(Typeface.MONOSPACE);
        tvParams.setTextColor(0xFF9AA0A6);
        tvParams.setLineSpacing(0, 1.15f);
        tvParams.setPadding(0, dp(4), 0, dp(2));
        addView(tvParams, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 时间 / 进度 / 时间 ----
        rowA = new LinearLayout(ctx);
        rowA.setOrientation(HORIZONTAL);
        rowA.setGravity(Gravity.CENTER_VERTICAL);
        rowA.setPadding(0, dp(6), 0, dp(2));

        tvElapsed = mono(ctx, "0:00", 11);
        rowA.addView(tvElapsed, new LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));

        seek = makeSeek(ctx);
        rowA.addView(seek, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvTotal = mono(ctx, "0:00", 11);
        tvTotal.setGravity(Gravity.END);
        rowA.addView(tvTotal, new LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(rowA);

        // ---- 六个控制键 ----
        rowB = new LinearLayout(ctx);
        rowB.setOrientation(HORIZONTAL);
        rowB.setGravity(Gravity.CENTER_VERTICAL);
        rowB.setPadding(0, dp(2), 0, 0);

        rowB.addView(key("−1分", v -> jump(-60000)));
        rowB.addView(key("−15", v -> jump(-15000)));

        btnPlay = new Button(ctx);
        btnPlay.setText("▶");
        btnPlay.setAllCaps(false);
        btnPlay.setTextSize(20);
        btnPlay.setTextColor(0xFF4C8DFF);
        btnPlay.setBackgroundColor(0x00000000);
        btnPlay.setOnClickListener(v -> toggle());
        rowB.addView(btnPlay, new LayoutParams(0, dp(48), 1.3f));

        rowB.addView(key("+15", v -> jump(15000)));
        rowB.addView(key("+1分", v -> jump(60000)));
        rowB.addView(key("■", v -> stopPlay()));

        addView(rowB, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 收起条：[▶] ▬▬●▬▬ [0:12] ----
        miniBar = new LinearLayout(ctx);
        miniBar.setOrientation(HORIZONTAL);
        miniBar.setGravity(Gravity.CENTER_VERTICAL);

        btnMiniPlay = new Button(ctx);
        btnMiniPlay.setText("▶");
        btnMiniPlay.setAllCaps(false);
        btnMiniPlay.setTextSize(18);
        btnMiniPlay.setTextColor(0xFF4C8DFF);
        btnMiniPlay.setBackgroundColor(0x00000000);
        btnMiniPlay.setOnClickListener(v -> toggle());
        miniBar.addView(btnMiniPlay, new LayoutParams(dp(44), dp(40)));

        seekMini = makeSeek(ctx);
        seekMini.setEnabled(false);   // 显示用；点击=展开，拖动=seek，由 miniBar 判断
        miniBar.addView(seekMini, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvMiniTime = mono(ctx, "0:00", 11);
        tvMiniTime.setGravity(Gravity.END);
        miniBar.addView(tvMiniTime, new LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT));

        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        final float[] downX = new float[1];
        final boolean[] dragging = new boolean[1];
        miniBar.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = e.getX();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging[0] && Math.abs(e.getX() - downX[0]) > slop) {
                        dragging[0] = true;
                    }
                    if (dragging[0] && durMs > 0) {
                        float left = seekMini.getLeft();
                        float w = Math.max(1, seekMini.getWidth());
                        float f = (e.getX() - left) / w;
                        if (f < 0) f = 0;
                        if (f > 1) f = 1;
                        int ms = (int) (f * durMs);
                        seekMini.setProgress((int) (f * STEP));
                        bars.setProgress(f);
                        tvMiniTime.setText(fmt(ms));
                        if (mp != null && prepared) {
                            try { mp.seekTo(ms); } catch (Throwable ignored) {}
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging[0]) {
                        setCollapsed(false);   // 轻点进度条 = 展开播放器
                    }
                    return true;
                default:
                    return false;
            }
        });

        miniBar.setVisibility(View.GONE);
        addView(miniBar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView mono(Context ctx, String text, int sp) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE);
        t.setText(text);
        return t;
    }

    private SeekBar makeSeek(Context ctx) {
        SeekBar s = new SeekBar(ctx);
        s.setMax(STEP);
        try {
            s.getProgressDrawable().setTint(0xFF4C8DFF);
            if (s.getThumb() != null) s.getThumb().setTint(0xFF4C8DFF);
        } catch (Throwable ignored) {}
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && durMs > 0) {
                    tvElapsed.setText(fmt((int) (p * (long) durMs / STEP)));
                    tvMiniTime.setText(fmt((int) (p * (long) durMs / STEP)));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (mp != null && prepared && durMs > 0) {
                    try {
                        int ms = (int) (sb.getProgress() * (long) durMs / STEP);
                        mp.seekTo(ms);
                        bars.setProgress(ms / (float) durMs);
                        seek.setProgress(sb.getProgress());
                        seekMini.setProgress(sb.getProgress());
                        tvElapsed.setText(fmt(ms));
                        tvMiniTime.setText(fmt(ms));
                    } catch (Throwable ignored) {}
                }
            }
        });
        return s;
    }

    private Button key(String text, OnClickListener l) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setBackgroundColor(0x00000000);
        b.setOnClickListener(l);
        b.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void collapse() {
        setCollapsed(true);
    }

    public void expand() {
        setCollapsed(false);
    }

    /** 收起/展开：只改可见性与高度，播放状态不动。 */
    private void setCollapsed(boolean collapse) {
        if (collapsed == collapse) return;
        collapsed = collapse;

        header.setVisibility(collapse ? View.GONE : View.VISIBLE);
        energyBox.setVisibility(collapse ? View.GONE : View.VISIBLE);
        rowA.setVisibility(collapse ? View.GONE : View.VISIBLE);
        rowB.setVisibility(collapse ? View.GONE : View.VISIBLE);
        tvParams.setVisibility(collapse ? View.GONE : View.VISIBLE);
        miniBar.setVisibility(collapse ? View.VISIBLE : View.GONE);
        if (listener != null) listener.onCollapsedChanged(collapse);

        // 高度随内容走，这里不再做高度动画，避免留出空白
    }

    public void open(RecordStore.Item item) {
        tvTitle.setText(item.line1());
        tvParams.setText(String.format(Locale.US,
                "时长 %d:%02d · %.1f MB · 16 kHz / 16 bit / 单声道%n%s",
                item.durMs / 60000, (item.durMs / 1000) % 60,
                item.size / 1048576.0,
                item.name == null ? "-" : item.name));
        tvElapsed.setText("0:00");
        tvTotal.setText("0:00");
        tvMiniTime.setText("0:00");
        seek.setProgress(0);
        seekMini.setProgress(0);
        bars.reset();
        durMs = (int) item.durMs;
        prepared = false;
        userSeeking = false;
        setCollapsed(false);
        release();
        ui.post(tick);

        new Thread(() -> {
            double[] e = AudioEnergy.scan(getContext(), item.uri, 120);
            ui.post(() -> bars.setEnergy(e));
        }, "energy-scan").start();

        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(getContext(), item.uri);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                durMs = p.getDuration();
                if (durMs <= 0) durMs = (int) item.durMs;
                tvTotal.setText(fmt(durMs));
                setPlayingUi(true);
                p.start();
            });
            mp.setOnCompletionListener(p -> {
                setPlayingUi(false);
                bars.setProgress(1f);
                seek.setProgress(STEP);
                seekMini.setProgress(STEP);
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            tvElapsed.setText("打不开");
        }
    }

    private void setPlayingUi(boolean playing) {
        btnPlay.setText(playing ? "❚❚" : "▶");
        btnMiniPlay.setText(playing ? "❚❚" : "▶");
    }

    private void toggle() {
        if (mp == null || !prepared) return;
        try {
            if (mp.isPlaying()) {
                mp.pause();
                setPlayingUi(false);
            } else {
                mp.start();
                setPlayingUi(true);
            }
        } catch (Throwable ignored) {}
    }

    private void jump(int deltaMs) {
        if (mp == null || !prepared) return;
        try {
            int pos = mp.getCurrentPosition() + deltaMs;
            if (pos < 0) pos = 0;
            if (durMs > 0 && pos > durMs) pos = durMs;
            mp.seekTo(pos);
            int p = durMs > 0 ? (int) (pos * (long) STEP / durMs) : 0;
            bars.setProgress(durMs > 0 ? pos / (float) durMs : 0);
            seek.setProgress(p);
            seekMini.setProgress(p);
            tvElapsed.setText(fmt(pos));
            tvMiniTime.setText(fmt(pos));
        } catch (Throwable ignored) {}
    }

    private void stopPlay() {
        if (mp == null || !prepared) return;
        try {
            mp.pause();
            mp.seekTo(0);
        } catch (Throwable ignored) {}
        setPlayingUi(false);
        bars.setProgress(0);
        seek.setProgress(0);
        seekMini.setProgress(0);
        tvElapsed.setText("0:00");
        tvMiniTime.setText("0:00");
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        return String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60);
    }

    public void release() {
        ui.removeCallbacks(tick);
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) {}
            try { p.release(); } catch (Throwable ignored) {}
        }
        setPlayingUi(false);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
