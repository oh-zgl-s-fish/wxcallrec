package com.zglinus.wxcallrec;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 只读通知监听：从微信的通话通知里取三件事——来电、通话中、通知消失。
 * 不看别的 App，不拿别人的内容。
 */
public class NotifyListener extends NotificationListenerService {

    public static final String WECHAT = "com.tencent.mm";

    public interface Hook {
        /** 来电通知：text 形如「好友昵称邀请你语音通话」。 */
        void onIncoming(String nick);

        /** 通话中通知：title 就是对方昵称。 */
        void onInCall(String nick);

        /** 微信的通知被移除（接听、拒接、挂断都会触发，语义靠时序区分）。 */
        void onGone();
    }

    public static volatile Hook hook;
    public static volatile boolean connected;

    private static final String KEY_INCOMING = "邀请你语音通话";
    private static final String KEY_INCALL = "语音通话中";

    @Override
    public void onListenerConnected() {
        connected = true;
        EventLog.add("NOTIF", "通知监听已连接");
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        EventLog.add("NOTIF", "通知监听已断开");
        if (hook != null) hook.onGone();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT.equals(sbn.getPackageName())) return;
        android.app.Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        String title = str(n, android.app.Notification.EXTRA_TITLE);
        String text = str(n, android.app.Notification.EXTRA_TEXT);

        Hook h = hook;

        if (text.contains(KEY_INCOMING)) {
            String nick = text.replace(KEY_INCOMING, "").trim();
            EventLog.add("NOTIF", "来电 «" + nick + "»");
            if (h != null) h.onIncoming(nick);
            return;
        }
        if (text.contains(KEY_INCALL) || title.contains(KEY_INCALL)) {
            String nick = title.contains(KEY_INCALL) ? "" : title.trim();
            EventLog.add("NOTIF", "通话中 «" + nick + "»");
            if (h != null) h.onInCall(nick);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT.equals(sbn.getPackageName())) return;
        EventLog.add("NOTIF", "微信通知移除");
        Hook h = hook;
        if (h != null) h.onGone();
    }

    private static String str(android.app.Notification n, String key) {
        CharSequence cs = n.extras.getCharSequence(key);
        return cs == null ? "" : cs.toString().trim();
    }
}
