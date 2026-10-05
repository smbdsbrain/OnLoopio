package io.onloopio.sync;
public final class IdlePolicy {
    public static boolean shutdown(boolean enabled,boolean playback,boolean usefulWork,boolean usb,boolean powered,long now,long last){return enabled && !playback && !usefulWork && !usb && !powered && now>=last && now-last>=1800000;}
}
