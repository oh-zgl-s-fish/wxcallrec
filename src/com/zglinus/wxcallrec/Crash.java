package com.zglinus.wxcallrec;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃落盘：没跑通就留证据，别静默死掉。
 * 位置：/sdcard/Android/data/com.zglinus.wxcallrec/files/crash-*.txt
 */
public final class Crash {

    private static boolean installed;

    private Crash() {}

    public static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(app, t, e);
            } catch (Throwable ignored) {
                // 落盘都失败就放弃，但绝不吞掉原来的崩溃
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void write(Context app, Thread t, Throwable e) throws Exception {
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        String stamp = new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(dir, "crash-" + stamp + ".txt");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time  " + new Date());
        pw.println("dev   " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        pw.println("os    Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
        pw.println("thread " + (t != null ? t.getName() : "?"));
        pw.println("---");
        e.printStackTrace(pw);
        pw.flush();

        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(sw.toString().getBytes("UTF-8"));
        }
        Log.e("AudioProbe", "crash written: " + f.getAbsolutePath());
    }
}
