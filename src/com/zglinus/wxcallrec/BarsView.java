package com.zglinus.wxcallrec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** 能量柱：已播放上色、未播置灰。只负责画，交互交给外层。 */
public class BarsView extends View {

    public interface Seek {
        /** fraction 0..1；commit=true 表示手指抬起，可以真正跳转。 */
        void onSeek(float fraction, boolean commit);
    }

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pendingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double[] energy = new double[0];
    private double peak = 1;
    private float progress;
    private Seek seekListener;
    private boolean dragging;
    private float downX;
    private int slop;

    public BarsView(Context ctx) {
        super(ctx);
        playedPaint.setColor(ctx.getColor(R.color.bar_played));
        pendingPaint.setColor(ctx.getColor(R.color.bar_pending));
        headPaint.setColor(ctx.getColor(R.color.bar_head));
        headPaint.setStrokeWidth(2.5f);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        setClickable(true);
    }

    public void setSeekListener(Seek l) {
        seekListener = l;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (energy.length == 0) return false;
        float f = e.getX() / Math.max(1, getWidth());
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                setProgress(f);
                if (seekListener != null) seekListener.onSeek(f, false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getX() - downX) > slop) dragging = true;
                setProgress(f);
                if (seekListener != null) seekListener.onSeek(f, false);
                return true;
            case MotionEvent.ACTION_UP:
                setProgress(f);
                if (seekListener != null) seekListener.onSeek(f, true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (seekListener != null) seekListener.onSeek(progress, true);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    public void setEnergy(double[] e) {
        energy = e != null ? e : new double[0];
        peak = 1;
        for (double v : energy) {
            double s = Math.sqrt(Math.max(0, v));
            if (s > peak) peak = s;
        }
        invalidate();
    }

    public void setProgress(float p) {
        if (p < 0) p = 0;
        if (p > 1) p = 1;
        if (Math.abs(p - progress) > 0.0005f) {
            progress = p;
            invalidate();
        }
    }

    public void reset() {
        energy = new double[0];
        progress = 0;
        peak = 1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int pad = 2;

        if (energy.length == 0) {
            canvas.drawRect(0, 0, w, h, pendingPaint);
            return;
        }

        float bw = w / (float) energy.length;
        for (int i = 0; i < energy.length; i++) {
            double s = Math.sqrt(Math.max(0, energy[i]));
            float ratio = (float) (s / peak);
            if (ratio > 1f) ratio = 1f;
            float bh = Math.max(2f, (h - pad * 2) * ratio);
            float left = i * bw + bw * 0.18f;
            float right = (i + 1) * bw - bw * 0.18f;
            boolean done = (i + 0.5f) / energy.length <= progress;
            canvas.drawRect(left, h - pad - bh, right, h - pad, done ? playedPaint : pendingPaint);
        }

        float x = progress * w;
        canvas.drawLine(x, 0, x, h, headPaint);
    }
}
