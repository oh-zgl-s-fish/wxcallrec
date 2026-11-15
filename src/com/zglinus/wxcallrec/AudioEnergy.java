package com.zglinus.wxcallrec;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedInputStream;
import java.io.InputStream;

/** 把一个 WAV 的能量按等分切成 N 段，供进度条画柱状图用。只读、不落盘。 */
public final class AudioEnergy {

    private static final int HEADER = 44;

    private AudioEnergy() {}

    public static double[] scan(Context ctx, Uri uri, int bars) {
        double[] out = new double[bars];
        long size;
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            size = pfd != null ? pfd.getStatSize() : 0;
        } catch (Exception e) {
            return out;
        }
        long dataBytes = size - HEADER;
        if (dataBytes <= 0) return out;
        long totalSamples = dataBytes / 2;
        if (totalSamples <= 0) return out;

        double[] sum = new double[bars];
        long[] cnt = new long[bars];

        try (InputStream is = new BufferedInputStream(
                ctx.getContentResolver().openInputStream(uri), 64 * 1024)) {
            if (is == null) return out;
            long skipped = 0;
            while (skipped < HEADER) {
                long s = is.skip(HEADER - skipped);
                if (s <= 0) {
                    if (is.read() < 0) return out;
                    s = 1;
                }
                skipped += s;
            }

            byte[] buf = new byte[64 * 1024];
            int carry = -1;
            long sampleIndex = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (carry < 0) {
                        carry = buf[i] & 0xFF;
                        continue;
                    }
                    int lo = carry;
                    int hi = buf[i] & 0xFF;
                    carry = -1;
                    short v = (short) (lo | (hi << 8));
                    int idx = (int) (sampleIndex * bars / totalSamples);
                    if (idx < 0) idx = 0;
                    if (idx >= bars) idx = bars - 1;
                    sum[idx] += (double) v * v;
                    cnt[idx]++;
                    sampleIndex++;
                }
            }
        } catch (Exception e) {
            return out;
        }

        for (int i = 0; i < bars; i++) {
            out[i] = cnt[i] > 0 ? Math.sqrt(sum[i] / cnt[i]) : 0;
        }
        return out;
    }
}
