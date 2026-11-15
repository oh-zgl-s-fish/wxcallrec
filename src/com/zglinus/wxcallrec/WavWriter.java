package com.zglinus.wxcallrec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** WAV 容器：16bit 单声道 PCM。头 44 字节，可流式拼。 */
public final class WavWriter {

    public static final int HEADER = 44;

    private WavWriter() {}

    public static byte[] header(int rate, int dataLen) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(HEADER);
        ascii(o, "RIFF");
        le32(o, 36 + dataLen);
        ascii(o, "WAVE");
        ascii(o, "fmt ");
        le32(o, 16);
        le16(o, 1);          // PCM
        le16(o, 1);          // 单声道
        le32(o, rate);
        le32(o, rate * 2);   // byte rate
        le16(o, 2);          // block align
        le16(o, 16);         // bits
        ascii(o, "data");
        le32(o, dataLen);
        return o.toByteArray();
    }

    public static void write(File f, short[] pcm, int rate) throws IOException {
        int dataLen = pcm.length * 2;
        byte[] buf = new byte[8192];
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(header(rate, dataLen));
            int idx = 0;
            int pos = 0;
            while (idx < pcm.length) {
                int n = Math.min(buf.length / 2, pcm.length - idx);
                for (int i = 0; i < n; i++) {
                    short s = pcm[idx + i];
                    buf[pos++] = (byte) (s & 0xFF);
                    buf[pos++] = (byte) ((s >> 8) & 0xFF);
                }
                os.write(buf, 0, pos);
                pos = 0;
                idx += n;
            }
        }
    }

    public static void copyHeader(OutputStream os, int rate, int dataLen) throws IOException {
        os.write(header(rate, dataLen));
    }

    private static void ascii(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }
}
