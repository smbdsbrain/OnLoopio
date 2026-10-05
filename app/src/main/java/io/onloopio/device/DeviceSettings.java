package io.onloopio.device;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import java.io.FileOutputStream;

/** Device preferences are independent of the private Navidrome account and library. */
public final class DeviceSettings {
    public static volatile long lastActivity=SystemClock.elapsedRealtime();
    private final Context context;
    private final SharedPreferences prefs;
    public DeviceSettings(Context context) { this.context=context.getApplicationContext(); prefs=this.context.getSharedPreferences("device",0);if(!prefs.contains("feedback_auto_sync"))prefs.edit().putBoolean("feedback_auto_sync",prefs.getBoolean("playlist_auto_sync",true)).commit(); }
    public boolean flag(String key,boolean fallback) { return prefs.getBoolean(key,fallback); }
    public int number(String key,int fallback) { return prefs.getInt(key,fallback); }
    public String text(String key,String fallback) { return prefs.getString(key,fallback); }
    public void setFlag(String key,boolean value) { if(!prefs.edit().putBoolean(key,value).commit()) throw new IllegalStateException("Cannot save device setting"); }
    public void setNumber(String key,int value) { if(!prefs.edit().putInt(key,value).commit()) throw new IllegalStateException("Cannot save device setting"); }
    public void setText(String key,String value) { if(!prefs.edit().putString(key,value).commit()) throw new IllegalStateException("Cannot save device setting"); }
    public void feedback() {
        if(flag("clicker",false)) ((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).playSoundEffect(AudioManager.FX_KEY_CLICK,0.3f);
        if(flag("vibration",false)) { Vibrator vibrator=(Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE); if(vibrator.hasVibrator()) vibrator.vibrate(12); }
    }
    public int timeout() { return Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT,30000); }
    public void timeout(int value) { if(!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT,value)) throw new IllegalStateException("Cannot change screen timeout"); }
    public int brightness() { return Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128); }
    public void brightness(int value) {
        Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        if(!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,value)) throw new IllegalStateException("Cannot change brightness");
    }
    public void wheelLocked(boolean locked) {
        // Vendor driver flag: stock Key Lock ON writes 0, OFF writes 1.
        // The kernel itself gates the wheel while the display is asleep.
        try { FileOutputStream file=new FileOutputStream("/proc/tpd_keys_enable"); try { file.write((locked?"0":"1").getBytes("US-ASCII")); } finally { file.close(); } }
        catch(Exception unavailable) { android.util.Log.w("OnLoopio","WHEEL_LOCK_UNAVAILABLE"); }
    }
    private PendingIntent shutdownIntent() { return PendingIntent.getBroadcast(context,42,new Intent(context,PowerReceiver.class).setAction("io.onloopio.POWER_TIMER"),PendingIntent.FLAG_UPDATE_CURRENT); }
    public void shutdownTimer(int minutes) {
        AlarmManager alarm=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE); alarm.cancel(shutdownIntent());
        long deadline=minutes==0 ? 0 : SystemClock.elapsedRealtime()+minutes*60000L;
        prefs.edit().putLong("shutdown_deadline",deadline).commit();
        if(deadline>0) alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,deadline,shutdownIntent());
    }
    public int shutdownRemaining() { long difference=prefs.getLong("shutdown_deadline",0)-SystemClock.elapsedRealtime(); return difference>0?(int)((difference+59999)/60000):0; }
    public void reset() { shutdownTimer(0); prefs.edit().clear().commit(); timeout(30000); brightness(128); wheelLocked(true); }
}
