package io.onloopio.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import io.onloopio.player.PlaybackService;
import io.onloopio.player.AudioCache;
import io.onloopio.sync.PlaylistSyncService;
import io.onloopio.sync.IdlePolicy;

public final class IdleReceiver extends BroadcastReceiver {
    public void onReceive(Context c,Intent intent){
        DeviceSettings settings=new DeviceSettings(c);
        Intent battery=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean powered=battery==null || battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
        boolean active=PlaybackService.state.playing;
        boolean work=AudioCache.activeTransfers()>0 || PlaylistSyncService.activeWorkers.get()>0 || io.onloopio.library.MusicLibraryService.running;
        boolean usb=settings.flag("usb_quiescing",false) || android.os.Environment.MEDIA_SHARED.equals(android.os.Environment.getExternalStorageState());
        long now=android.os.SystemClock.elapsedRealtime();
        if(IdlePolicy.shutdown(settings.flag("idle_shutdown",false),active,work,usb,powered,now,DeviceSettings.lastActivity))PowerReceiver.powerOff(c);
        else {if(active || powered || usb)IdleScheduler.activity(c);IdleScheduler.ensure(c);}
    }
}
