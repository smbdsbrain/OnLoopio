package io.onloopio.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import io.onloopio.device.DeviceSettings;
import io.onloopio.device.OnlineMode;

public final class SyncReceiver extends BroadcastReceiver {
    private static PowerManager.WakeLock handoff;
    private static synchronized void hold(Context c){if(handoff==null){handoff=((PowerManager)c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OnLoopio:sync-handoff");handoff.setReferenceCounted(false);}handoff.acquire(60000);}
    static synchronized void releaseHandoff(){if(handoff!=null && handoff.isHeld())handoff.release();}
    public void onReceive(Context c,Intent intent){
        String action=intent.getAction();if(Intent.ACTION_POWER_CONNECTED.equals(action)||Intent.ACTION_POWER_DISCONNECTED.equals(action))io.onloopio.device.IdleScheduler.activity(c);DeviceSettings settings=new DeviceSettings(c);String reason=null;
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)){
            c.getSharedPreferences("sync_schedule",0).edit().putBoolean("home_connected",false).commit();SyncScheduler.ensure(c,true);reason="startup";
        }else if(SyncScheduler.PERIOD.equals(action) && settings.number("sync_interval_minutes",SyncScheduler.DEFAULT_MINUTES)>0)reason="timer";
        else if(Intent.ACTION_POWER_CONNECTED.equals(action) && settings.flag("sync_on_charge",true))reason="charger connected";
        else if(Intent.ACTION_POWER_DISCONNECTED.equals(action) && settings.flag("sync_on_unplug",true))reason="charger disconnected";
        else if(WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action) || ConnectivityManager.CONNECTIVITY_ACTION.equals(action)){
            SharedPreferences state=c.getSharedPreferences("sync_schedule",0);boolean home=new OnlineMode(c).homeWifi(),was=state.getBoolean("home_connected",false);
            state.edit().putBoolean("home_connected",home).commit();if(home && !was && settings.flag("sync_on_wifi",true)){WorkGate.networkReturned(c);reason="home Wi-Fi connected";}
        }
        if(reason!=null && (settings.flag("playlist_auto_sync",true) || settings.flag("feedback_auto_sync",true))){SyncScheduler.ensure(c,true);hold(c);try{PlaylistSyncService.request(c,reason,false,null);}catch(RuntimeException unavailable){releaseHandoff();android.util.Log.w("OnLoopio","PLAYLIST_SYNC_START_FAILED");}}
    }
}
