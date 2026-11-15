package com.zglinus.wxcallrec;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 内存里的时间轴，供界面和导出用。信号探针同款，这里只留 200 条。
 */
public final class EventLog {

    public static final class E {
        public final long ts;
        public final String kind;
        public final String text;

        E(long ts, String kind, String text) {
            this.ts = ts;
            this.kind = kind;
            this.text = text;
        }
    }

    private static final int MAX = 200;
    private static final ArrayList<E> LIST = new ArrayList<>();

    private EventLog() {}

    public static void add(String kind, String text) {
        synchronized (LIST) {
            LIST.add(new E(System.currentTimeMillis(), kind, text));
            while (LIST.size() > MAX) LIST.remove(0);
        }
    }

    public static int size() {
        synchronized (LIST) {
            return LIST.size();
        }
    }

    public static List<E> slice(int from) {
        List<E> out = new ArrayList<>();
        synchronized (LIST) {
            if (from < 0) from = 0;
            for (int i = from; i < LIST.size(); i++) out.add(LIST.get(i));
        }
        return out;
    }

    public static void clear() {
        synchronized (LIST) {
            LIST.clear();
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LIST) {
            for (E e : LIST) {
                sb.append(String.format("%tT.%03d  %-9s %s%n",
                        new Date(e.ts), e.ts % 1000, e.kind, e.text));
            }
        }
        return sb.toString();
    }
}
