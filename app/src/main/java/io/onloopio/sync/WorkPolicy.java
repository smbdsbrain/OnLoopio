package io.onloopio.sync;
/** Shared API-17 policy. It never enables or disables another owner's radio. */
public final class WorkPolicy {
    public enum Work { FEEDBACK,PLAYLIST,CATALOG,DOWNLOAD }
    public static final class Input {public boolean offline,usb,home,charging,low,manual,chargeProfile,enabled=true;public int battery=-1;public long now,next;}
    public static String reason(Input i,Work work){
        if(i.offline)return "Forced offline";if(i.usb)return "USB storage shared with computer";
        if(i.battery>=0 && i.battery<=5 && !i.charging)return "Critical battery";
        if(!i.enabled && !i.manual)return "Automatic sync disabled";
        if(!i.home)return "Waiting for home Wi-Fi";
        if(!i.manual && i.next>i.now)return "Waiting for retry";
        if(work==Work.DOWNLOAD || work==Work.CATALOG){if(!i.charging && (i.low || i.battery<0))return "Waiting for battery";if(i.chargeProfile && !i.charging && !i.manual)return "Waiting for charger";}
        return "";
    }
    public static boolean cancelled(String reason){return reason.equals("Forced offline") || reason.equals("USB storage shared with computer") || reason.equals("Critical battery") || reason.equals("Waiting for home Wi-Fi");}
    public static boolean low(boolean previous,int battery){return battery<0?previous:battery<=20 || previous && battery<25;}
    public static long retryAt(long now,int failures,double jitter){return now+FeedbackRetry.delay(failures)+(long)(Math.max(0,Math.min(1,jitter))*15000);}
    public static long boundedNext(long now,long saved){return Math.max(now,Math.min(now+3615000,saved));}
}
