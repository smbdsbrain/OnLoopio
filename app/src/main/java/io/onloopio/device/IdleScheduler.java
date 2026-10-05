package io.onloopio.device;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** An idle timer must also run when no PlaybackService exists. */
public final class IdleScheduler {
    private static volatile long lastScheduled;
    private static PendingIntent alarm(Context c){return PendingIntent.getBroadcast(c,73,new Intent(c,IdleReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT);}
    public static void activity(Context c){DeviceSettings.lastActivity=android.os.SystemClock.elapsedRealtime();if(DeviceSettings.lastActivity-lastScheduled>=10000)ensure(c);}
    public static void ensure(Context c){
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);DeviceSettings settings=new DeviceSettings(c);PendingIntent pending=alarm(c);
        if(!settings.flag("idle_shutdown",false)){manager.cancel(pending);return;}
        long now=android.os.SystemClock.elapsedRealtime();lastScheduled=now;
        manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,Math.max(now+5000,DeviceSettings.lastActivity+30*60000L),pending);
    }
}
