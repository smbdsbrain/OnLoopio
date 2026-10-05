package io.onloopio;

import android.app.Application;
import io.onloopio.device.DeviceSettings;

public final class OnLoopioApplication extends Application {
    public void onCreate() {
        super.onCreate(); io.onloopio.device.ControlConfigStore.importPrivateFile(this);io.onloopio.config.UsbSetup.importFromUsb(this);DeviceSettings settings=new DeviceSettings(this);
        settings.wheelLocked(settings.flag("key_lock",true));
        io.onloopio.sync.SyncScheduler.ensure(this,false);
        io.onloopio.library.MusicLibraryService.request(this,false);
        io.onloopio.player.PlaybackService.action(this,io.onloopio.player.PlaybackService.KICK);
    }
}
