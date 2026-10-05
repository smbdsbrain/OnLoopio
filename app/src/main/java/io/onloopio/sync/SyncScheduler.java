package io.onloopio.sync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import io.onloopio.device.DeviceSettings;

/** API-17 alarms continue when the player screen is asleep or the process is gone. */
public final class SyncScheduler {
    public static final String PERIOD="io.onloopio.CHECK_PLAYLISTS_PERIODIC";
    public static final int DEFAULT_MINUTES=15;
    private static PendingIntent alarm(Context c){return PendingIntent.getBroadcast(c,71,new Intent(c,SyncReceiver.class).setAction(PERIOD),PendingIntent.FLAG_UPDATE_CURRENT);}
    public static void ensure(Context c,boolean reset){
        DeviceSettings settings=new DeviceSettings(c);int minutes=settings.number("sync_interval_minutes",DEFAULT_MINUTES);
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);PendingIntent pending=alarm(c);
        if(!(settings.flag("playlist_auto_sync",true) || settings.flag("feedback_auto_sync",true)) || minutes<=0){manager.cancel(pending);return;}
        minutes=Math.max(5,Math.min(1440,minutes));SharedPreferences state=c.getSharedPreferences("sync_schedule",0);long now=android.os.SystemClock.elapsedRealtime();
        long at=state.getLong("alarm_at_elapsed",0);if(reset || state.getInt("minutes",0)!=minutes || at==0)at=now+minutes*60000L;
        // Skip missed checks after downtime; do not produce a burst of stale alarms.
        if(at<=now)at+=((now-at)/(minutes*60000L)+1)*minutes*60000L;
        state.edit().putLong("alarm_at_elapsed",at).putInt("minutes",minutes).commit();
        manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,at,pending);
    }
}
