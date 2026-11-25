package com.zglinus.wxcallrec;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import java.util.Locale;

/**
 * 开录判定的唯一来源：看微信有没有进入通话界面。
 *
 * 只读事件的包名与类名（AccessibilityEvent.getClassName），**不读窗口内容**，
 * 所以 a11y_config 里 canRetrieveWindowContent=false，不拿节点树、不采集屏幕文字。
 * 昵称仍然从通知里取（NotifyListener），这里不管。
 */
public class MicA11y extends AccessibilityService {

    private static final String WX = "com.tencent.mm";
    private boolean inVoip;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || !WX.contentEquals(pkg)) return;

        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        boolean voip = cls.toLowerCase(Locale.US).contains("voip");

        if (voip && !inVoip) {
            inVoip = true;
            EventLog.add("A11Y", "进入通话界面 · " + shortName(cls));
        } else if (!voip && inVoip) {
            inVoip = false;
            EventLog.add("A11Y", "离开通话界面 → " + shortName(cls));
        }
        if (!voip && !inVoip) return;   // 普通聊天窗口不上报，免得刷屏

        VoipHook h = RecordService.voipHook;
        if (h != null) {
            h.onVoipWindow(cls, voip);
        } else {
            EventLog.add("A11Y", "录音服务未运行，信号丢弃");
        }
    }

    @Override
    public void onInterrupt() {
        inVoip = false;
    }

    private static String shortName(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }
}
