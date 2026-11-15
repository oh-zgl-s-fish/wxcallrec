package com.zglinus.wxcallrec;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 可选的无障碍服务，不参与录音逻辑。
 * 实测：不开它也能在通话中录到音。留着只为「万一哪天录成静音」时有个可试的开关。
 * 不读窗口内容、不监听事件、不采集任何数据。
 */
public class MicA11y extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 故意留空
    }

    @Override
    public void onInterrupt() {
        // 故意留空
    }
}
