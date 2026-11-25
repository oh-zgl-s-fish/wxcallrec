package com.zglinus.wxcallrec;

/** 无障碍服务把「微信通话界面进出」推给录音服务。 */
public interface VoipHook {
    void onVoipWindow(String className, boolean isVoip);
}
