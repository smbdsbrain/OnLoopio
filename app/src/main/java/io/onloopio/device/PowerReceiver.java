package io.onloopio.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PowerReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent) {
        DeviceSettings settings=new DeviceSettings(context);
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) { settings.shutdownTimer(0);IdleScheduler.activity(context);IdleScheduler.ensure(context); settings.wheelLocked(settings.flag("key_lock",true)); return; }
        if("io.onloopio.POWER_TIMER".equals(intent.getAction())) { settings.shutdownTimer(0); powerOff(context); }
    }
    public static void powerOff(Context context) {
        final Context app=context.getApplicationContext();io.onloopio.player.PlaybackService.action(app,io.onloopio.player.PlaybackService.QUIESCE);app.stopService(new Intent(app,io.onloopio.sync.PlaylistSyncService.class));
        final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());final long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        handler.postDelayed(new Runnable(){public void run(){if(io.onloopio.player.PlaybackService.state.song!=null || io.onloopio.player.PlaybackService.state.busy || io.onloopio.player.PlaybackService.checkpoints.get()>0 || io.onloopio.sync.PlaylistSyncService.activeWorkers.get()+io.onloopio.player.AudioCache.activeTransfers()>0 || io.onloopio.library.MusicLibraryService.running){if(android.os.SystemClock.elapsedRealtime()<deadline)handler.postDelayed(this,200);return;}requestShutdown(app);}},200);
    }
    private static void requestShutdown(Context context){
        // SHUTDOWN is signature|system on the verified API-17 firmware. No root daemon is used.
        context.startActivity(new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")
                .putExtra("android.intent.extra.KEY_CONFIRM",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
