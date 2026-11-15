package com.zglinus.wxcallrec;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/** 开机后按需把自动录音拉起来。只在「开机自启」与「自动录音」都开着时动手。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!p.getBoolean("boot", false)) return;
        if (!p.getBoolean("auto", false)) {
            EventLog.add("BOOT", "开机：自动录音未开，跳过");
            return;
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            EventLog.add("BOOT", "开机：没有麦克风权限，跳过");
            return;
        }
        try {
            Intent svc = new Intent(ctx, RecordService.class).setAction(RecordService.ACTION_START);
            ctx.startForegroundService(svc);
            EventLog.add("BOOT", "开机自启：已恢复自动录音");
        } catch (Throwable t) {
            EventLog.add("BOOT", "开机自启失败: " + t.getMessage());
        }
    }
}
