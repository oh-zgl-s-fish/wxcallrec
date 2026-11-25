package com.zglinus.wxcallrec;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 录音器：VOICE_RECOGNITION，16k 单声道，30 秒一段。
 *
 * 关键约束（实测得出，别改）：**必须在通话建立之后才创建 AudioRecord**。
 * 响铃阶段就创建的那条流，通话一建立会被降级静音，而且不会自己恢复。
 * 探针之所以能录到，就是因为它每次都在通话中新建。
 *
 * 代价：接听到开录之间有约 0.2–0.5 秒空档，补不回来（Android 不给补历史）。
 */
public final class Recorder {

    public interface Listener {
        void onSegment(File file, int count, long totalMs);

        void onSilent(long silentMs);

        void onError(String msg);
    }

    public static final int RATE = 16000;
    private static final int SEG_MS = 30000;
    private static final int SILENT_WATCH_MS = 5000;

    private final File dir;
    private final Listener listener;
    private final String stamp;

    private AudioRecord rec;
    private Thread thread;
    private volatile boolean running;

    private volatile int segments;
    private volatile long totalMs;
    private volatile boolean sawSignal;
    private volatile Boolean silenced;
    private volatile double levelRms;     // 最近 1 秒的 RMS（0..32767）
    private volatile int levelPeak;       // 最近 1 秒的峰值采样

    private short[] segBuf;
    private int segPos;

    public Recorder(File dir, Listener listener) {
        this.dir = dir;
        this.listener = listener;
        this.stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /** 打开麦克风并立即开始写。必须在对的时机调用——通话已经建立。 */
    public synchronized boolean start() {
        if (running) return true;
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                listener.onError("设备不支持 16k 单声道 PCM16");
                return false;
            }
            // 与探针 Probe.probe() 完全一致：100ms 一块、按 4 块算、不小于 minBufferSize
            int chunkSamples = Math.max(RATE / 10, 160);
            int bufBytes = Math.max(chunkSamples * 2 * 4, minBuf);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                listener.onError("AudioRecord 初始化失败 state=" + rec.getState());
                return false;
            }
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                listener.onError("startRecording 失败 state=" + rec.getRecordingState());
                return false;
            }
        } catch (SecurityException e) {
            listener.onError("被拒绝: " + e.getMessage());
            return false;
        } catch (Exception e) {
            listener.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }

        if (!dir.exists() && !dir.mkdirs()) {
            listener.onError("建不了目录: " + dir);
            return false;
        }

        segBuf = new short[RATE * SEG_MS / 1000];
        segPos = 0;
        segments = 0;
        totalMs = 0;
        sawSignal = false;
        silenced = null;

        running = true;
        thread = new Thread(this::loop, "call-recorder");
        thread.start();
        return true;
    }

    public synchronized void stop(boolean save) {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
        closeRecord();
        if (!save) {
            segBuf = null;
            segments = 0;
        }
    }

    public String stamp() {
        return stamp;
    }

    public long totalMs() {
        return totalMs;
    }

    public boolean sawSignal() {
        return sawSignal;
    }

    /** 最近一次读到的系统静音状态；null 表示这台机器取不到。 */
    /** 最近 1 秒的 RMS（0..32767）。 */
    public double levelRms() {
        return levelRms;
    }

    /** 最近 1 秒的峰值采样（0..32767）。 */
    public int levelPeak() {
        return levelPeak;
    }

    public Boolean silenced() {
        return silenced;
    }

    private void closeRecord() {
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void loop() {
        short[] chunk = new short[RATE / 10];
        long silentMs = 0;
        long windowStart = System.currentTimeMillis();
        int windowPeak = 0;

        // 量化指标窗口：1 秒
        long metricStart = System.currentTimeMillis();
        long metricSumSq = 0;
        long metricCount = 0;
        int metricPeak = 0;

        try {
            while (running) {
                int read = rec != null ? rec.read(chunk, 0, chunk.length) : -1;
                if (read <= 0) {
                    if (read < 0) {
                        listener.onError("read=" + read);
                        break;
                    }
                    continue;
                }

                for (int i = 0; i < read; i++) {
                    int v = chunk[i];
                    int a = v < 0 ? -v : v;
                    if (a > windowPeak) windowPeak = a;
                    if (a > metricPeak) metricPeak = a;
                    metricSumSq += (long) v * v;
                }
                metricCount += read;

                long now = System.currentTimeMillis();

                // 每 1 秒刷新一次量化指标
                if (now - metricStart >= 1000) {
                    levelRms = metricCount > 0 ? Math.sqrt((double) metricSumSq / metricCount) : 0;
                    levelPeak = metricPeak;
                    metricSumSq = 0;
                    metricCount = 0;
                    metricPeak = 0;
                    metricStart = now;
                }

                if (now - windowStart >= SILENT_WATCH_MS) {
                    if (windowPeak == 0) {
                        silentMs += now - windowStart;
                        listener.onSilent(silentMs);
                    } else {
                        silentMs = 0;
                    }
                    windowPeak = 0;
                    windowStart = now;
                    pollSilenced();
                }

                append(chunk, read);
            }
        } catch (Exception e) {
            listener.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (segPos > 0) {
                flush(segPos);
                segPos = 0;
            }
            closeRecord();
        }
    }

    private void pollSilenced() {
        AudioRecord r = rec;
        if (r == null) return;
        try {
            AudioRecordingConfiguration c = r.getActiveRecordingConfiguration();
            if (c != null) silenced = c.isClientSilenced();
        } catch (Throwable ignored) {
            // 某些机型这组 API 会抛，保留 null 的事实
        }
    }

    private void append(short[] src, int n) {
        if (segBuf == null) return;
        int off = 0;
        while (off < n) {
            int copy = Math.min(n - off, segBuf.length - segPos);
            System.arraycopy(src, off, segBuf, segPos, copy);
            for (int i = 0; i < copy; i++) {
                if (src[off + i] != 0) sawSignal = true;
            }
            segPos += copy;
            off += copy;
            if (segPos >= segBuf.length) {
                flush(segPos);
                segPos = 0;
            }
        }
    }

    private void flush(int len) {
        if (len <= 0 || segBuf == null) return;
        segments++;
        File f = new File(dir, "seg_" + stamp + "_" + String.format(Locale.US, "%03d", segments) + ".wav");
        try {
            short[] out;
            if (len == segBuf.length) {
                out = segBuf;
            } else {
                out = new short[len];
                System.arraycopy(segBuf, 0, out, 0, len);
            }
            WavWriter.write(f, out, RATE);
            totalMs += len * 1000L / RATE;
            listener.onSegment(f, segments, totalMs);
        } catch (Exception e) {
            listener.onError("写盘失败: " + e.getMessage());
        }
    }

    // ---------- 自检：与探针完全相同的采集路径 ----------

    public static final class SelfCheck {
        public int rate;
        public long samples;
        public int peak;
        public double rms;
        public String error = "";

        public String verdict() {
            if (!error.isEmpty()) return "失败";
            if (samples == 0) return "无数据";
            if (peak == 0) return "全零";
            if (rms < 6) return "近静音";
            if (rms < 40) return "弱信号";
            return "有信号";
        }
    }

    public static SelfCheck selfTest(int seconds) {
        SelfCheck s = new SelfCheck();
        s.rate = RATE;
        AudioRecord r = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                s.error = "不支持 16k 单声道 PCM16";
                return s;
            }
            int chunkSamples = Math.max(RATE / 10, 160);
            int bufBytes = Math.max(chunkSamples * 2 * 4, minBuf);

            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                s.error = "初始化失败 state=" + r.getState();
                return s;
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                s.error = "startRecording 失败 state=" + r.getRecordingState();
                return s;
            }

            short[] buf = new short[chunkSamples];
            long peak = 0, total = 0;
            double sumSq = 0;
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                int n = r.read(buf, 0, buf.length);
                if (n < 0) {
                    s.error = "read=" + n;
                    break;
                }
                if (n == 0) continue;
                for (int i = 0; i < n; i++) {
                    int v = buf[i];
                    int a = v < 0 ? -v : v;
                    if (a > peak) peak = a;
                    sumSq += (double) v * v;
                }
                total += n;
            }
            s.samples = total;
            s.peak = (int) peak;
            s.rms = total > 0 ? Math.sqrt(sumSq / total) : 0;
        } catch (Exception e) {
            s.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (r != null) {
                try { r.stop(); } catch (Exception ignored) {}
                try { r.release(); } catch (Exception ignored) {}
            }
        }
        return s;
    }
}
