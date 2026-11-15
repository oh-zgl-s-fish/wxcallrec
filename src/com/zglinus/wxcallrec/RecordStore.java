package com.zglinus.wxcallrec;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 录音文件列表：Recordings/（Audio 集合）与旧的 Download/通话录音/ 都查。 */
public final class RecordStore {

    public static final class Item {
        public Uri uri;
        public String name;
        public String nick;
        public long size;
        public long durMs;

        public String line1() {
            return nick == null || nick.isEmpty() ? "未知" : nick;
        }

        public String line2() {
            String d = "";
            Matcher m = P.matcher(name == null ? "" : name);
            if (m.matches()) {
                String stamp = m.group(2);
                if (stamp.length() == 15) {
                    d = stamp.substring(4, 6) + "-" + stamp.substring(6, 8)
                            + " " + stamp.substring(9, 11) + ":" + stamp.substring(11, 13);
                }
            }
            return String.format(Locale.US, "%s · %d:%02d · %.1f MB",
                    d, durMs / 60000, (durMs / 1000) % 60, size / 1048576.0);
        }
    }

    private static final Pattern P =
            Pattern.compile("^call_(.*)_(\\d{8}_\\d{6})_(\\d+)m(\\d+)s\\.wav$");

    private RecordStore() {}

    public static List<Item> list(Context ctx) {
        Map<String, Item> map = new LinkedHashMap<>();
        query(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, map);
        query(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, map);

        List<Item> out = new ArrayList<>(map.values());
        out.sort((a, b) -> Long.compare(b.durMs == 0 ? 0 : 1, a.durMs == 0 ? 0 : 1));
        return out;
    }

    private static void query(Context ctx, Uri base, Map<String, Item> map) {
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        };
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = {"call_%.wav"};

        try (Cursor c = ctx.getContentResolver().query(base, proj, sel, args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            while (c.moveToNext()) {
                String name = c.getString(nameIdx);
                if (name == null || map.containsKey(name)) continue;
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(base, c.getLong(idIdx));
                it.name = name;
                it.size = c.getLong(sizeIdx);
                Matcher m = P.matcher(name);
                if (m.matches()) {
                    it.nick = m.group(1);
                    it.durMs = (Long.parseLong(m.group(3)) * 60 + Long.parseLong(m.group(4))) * 1000;
                }
                map.put(name, it);
            }
        } catch (Exception ignored) {
            // 某个集合读不到就跳过
        }
    }
}
