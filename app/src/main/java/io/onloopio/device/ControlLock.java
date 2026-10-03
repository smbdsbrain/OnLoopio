package io.onloopio.device;

import android.content.Context;
import android.view.KeyEvent;

/** App control lock is independent of the vendor's screen-off wheel policy. */
public final class ControlLock {
    public static final String CHANGED="io.onloopio.CONTROLS_CHANGED";
    private ControlLock() {}
    public static boolean locked(Context context) { return new DeviceSettings(context).flag("controls_locked",false); }
    public static void locked(Context context,boolean value) { new DeviceSettings(context).setFlag("controls_locked",value);context.sendBroadcast(new android.content.Intent(CHANGED).setPackage(context.getPackageName())); }
    public static boolean blocks(int code) {
        return Y1Keys.previousRow(code) || Y1Keys.nextRow(code) || code==KeyEvent.KEYCODE_BACK ||
                code==KeyEvent.KEYCODE_MENU || media(code);
    }
    public static boolean media(int code) {
        return code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code==KeyEvent.KEYCODE_MEDIA_PLAY ||
                code==KeyEvent.KEYCODE_MEDIA_PAUSE || code==KeyEvent.KEYCODE_MEDIA_NEXT ||
                code==KeyEvent.KEYCODE_MEDIA_PREVIOUS || code==KeyEvent.KEYCODE_MEDIA_STOP ||
                code==KeyEvent.KEYCODE_MEDIA_REWIND || code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                code==KeyEvent.KEYCODE_HEADSETHOOK;
    }
}
