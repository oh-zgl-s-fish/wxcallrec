package com.zglinus.wxcallrec;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 把分段落盘的文件拼成一个完整通话录音。
 *
 * 落点优先 Recordings/（Android 12+ 的录音目录，属于 Audio 集合）；
 * MediaStore 对集合与目录有强校验——Downloads 集合只允许 Download/ 开头，
 * 往里塞 Recordings 会被直接拒掉，所以这里按集合分别要位置。
 */
public final class CallStore {

    public static final String SUBDIR_LEGACY = Environment.DIRECTORY_DOWNLOADS + "/通话录音";

    private CallStore() {}

    public static Uri merge(Context ctx, List<File> segs, int rate, String name) throws Exception {
        long dataLen = 0;
        for (File f : segs) {
            dataLen += Math.max(0, f.length() - WavWriter.HEADER);
        }

        Exception first = null;

        // 1) 首选：Audio 集合 + Recordings（API 31+）
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return writeInto(ctx, MediaStore.Audio.Media.getContentUri(
                                MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        Environment.DIRECTORY_RECORDINGS, segs, rate, name, (int) dataLen);
            } catch (Exception e) {
                first = e;
            }
        }

        // 2) 退路：Download/通话录音（所有版本都允许）
        try {
            return writeInto(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    SUBDIR_LEGACY, segs, rate, name, (int) dataLen);
        } catch (Exception e) {
            if (first != null) {
                throw new IllegalStateException("Recordings: " + first.getMessage()
                        + " / Download: " + e.getMessage());
            }
            throw e;
        }
    }

    private static Uri writeInto(Context ctx, Uri collection, String relPath,
                                 List<File> segs, int rate, String name, int dataLen)
            throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        Uri uri = ctx.getContentResolver().insert(collection, v);
        if (uri == null) throw new IllegalStateException("insert 返回 null: " + relPath);

        boolean ok = false;
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("openOutputStream 返回 null");
            os.write(WavWriter.header(rate, dataLen));
            byte[] buf = new byte[8192];
            for (File f : segs) {
                try (InputStream is = new FileInputStream(f)) {
                    long skip = is.skip(WavWriter.HEADER);
                    if (skip != WavWriter.HEADER) {
                        throw new IllegalStateException("段文件头损坏: " + f.getName());
                    }
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
            ok = true;
        } finally {
            if (!ok) {
                try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try { ctx.getContentResolver().update(uri, done, null, null); } catch (Exception ignored) {}
        }
        return uri;
    }
}
