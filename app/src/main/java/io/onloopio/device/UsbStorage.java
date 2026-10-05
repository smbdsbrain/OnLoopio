package io.onloopio.device;
import android.content.Context;

/** Stock API-17 mount service, used through its hidden StorageManager methods. */
public final class UsbStorage {
    private static Object storage(Context c){return c.getSystemService(Context.STORAGE_SERVICE);}
    private static Object invoke(Context c,String method)throws Exception {Object manager=storage(c);return manager.getClass().getMethod(method).invoke(manager);}
    public static boolean connected(Context c)throws Exception{return Boolean.TRUE.equals(invoke(c,"isUsbMassStorageConnected"));}
    public static boolean enabled(Context c)throws Exception{return Boolean.TRUE.equals(invoke(c,"isUsbMassStorageEnabled"));}
    public static void enable(Context c)throws Exception{
        if(!connected(c))throw new IllegalStateException("Connect the USB cable first");if(c.checkCallingOrSelfPermission("android.permission.MOUNT_UNMOUNT_FILESYSTEMS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("USB storage requires system installation");DeviceSettings prefs=new DeviceSettings(c);
        prefs.setFlag("usb_downloads_were_paused",prefs.flag("downloads_paused",false));prefs.setFlag("usb_restore_downloads",true);
        prefs.setFlag("usb_quiescing",true);c.stopService(new android.content.Intent(c,io.onloopio.sync.PlaylistSyncService.class));
        io.onloopio.player.PlaybackService.action(c,io.onloopio.player.PlaybackService.CANCEL);
        io.onloopio.player.PlaybackService.action(c,io.onloopio.player.PlaybackService.QUIESCE);
        final Context application=c.getApplicationContext();final long deadline=android.os.SystemClock.uptimeMillis()+10000;final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        io.onloopio.library.MusicLibraryService.status="Preparing USB storage…";
        handler.postDelayed(new Runnable(){public void run(){
            if(io.onloopio.player.PlaybackService.checkpoints.get()!=0 || io.onloopio.sync.PlaylistSyncService.busy || io.onloopio.player.PlaybackService.state.song!=null || io.onloopio.player.PlaybackService.state.busy || io.onloopio.sync.PlaylistSyncService.activeWorkers.get()+io.onloopio.player.AudioCache.activeTransfers()!=0 || io.onloopio.library.MusicLibraryService.running){if(android.os.SystemClock.uptimeMillis()<deadline){handler.postDelayed(this,200);return;}io.onloopio.library.MusicLibraryService.status="Wait for music scanning or downloads before sharing USB storage";mounted(application);return;}
            try{invoke(application,"enableUsbMassStorage");}catch(Exception failed){io.onloopio.library.MusicLibraryService.status="USB storage unavailable";mounted(application);android.util.Log.w("OnLoopio","USB_STORAGE_FAILED",failed);}
        }},1000);
    }
    public static void disable(Context c)throws Exception{invoke(c,"disableUsbMassStorage");}
    public static void mounted(Context c){DeviceSettings prefs=new DeviceSettings(c);prefs.setFlag("usb_quiescing",false);if(prefs.flag("usb_restore_downloads",false)){prefs.setFlag("downloads_paused",prefs.flag("usb_downloads_were_paused",false));prefs.setFlag("usb_restore_downloads",false);io.onloopio.player.PlaybackService.action(c,io.onloopio.player.PlaybackService.KICK);}}
}
