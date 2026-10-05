package io.onloopio.library;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.PowerManager;
import io.onloopio.api.ServerConfig;
import io.onloopio.config.ConfigStore;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.DeviceSettings;
import io.onloopio.player.AudioCache;
import java.io.File;
import java.util.List;

public final class MusicLibraryService extends IntentService {
    public static final String UPDATED="io.onloopio.LOCAL_LIBRARY_UPDATED";
    public static volatile String status="Ready";public static volatile boolean running;
    private static boolean queued,again;
    public MusicLibraryService(){super("OnLoopio local music");}
    public static synchronized void request(Context context,boolean force){if(queued){if(force)again=true;return;}queued=true;context.startService(new Intent(context,MusicLibraryService.class).putExtra("force",force));}
    protected void onHandleIntent(Intent intent){
        PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OnLoopio:music-scan");wake.acquire(1200000);MetadataStore store=new MetadataStore(this);DeviceSettings prefs=new DeviceSettings(this);
        boolean notify=false;try{
            // Private staging guard allows hardware migration tests before touching production audio.
            if(new File(getFilesDir(),"music-library.hold").exists())return;
            if(prefs.flag("usb_restore_downloads",false))return;
            if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())){status="Storage unavailable · reconnect USB in player mode";return;}
            long last=prefs.number("local_scan_seconds",0)*1000L;if(!intent.getBooleanExtra("force",false) && System.currentTimeMillis()-last<60000)return;
            notify=true;running=true;status="Moving downloaded music…";File root=MusicPaths.root();if(!root.isDirectory() && !root.mkdirs())throw new java.io.IOException("Cannot create Music");
            ServerConfig config=new ConfigStore(this).load();int moved=0;if(config!=null && config.accountKey().equals(store.accountKey())){AudioCache cache=new AudioCache(this,config);moved=cache.migrate(store.offlineSongs(cache.legacyNames()));}
            AudioFileIndex files=new AudioFileIndex(this);java.util.Set<String> owned;try{owned=files.ownedPaths();}finally{files.close();}
            List<MetadataStore.LocalEntry> previous=store.localEntries();java.util.Set<MetadataStore.LocalEntry> unchanged=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MetadataStore.LocalEntry,Boolean>());unchanged.addAll(previous);status="Scanning Music…";List<MetadataStore.LocalEntry> snapshot=new LocalMusicScanner().scan(root,owned,previous,new LocalMusicScanner.Progress(){public void found(int count){status="Scanning Music · "+count+" tracks";}});
            synchronized(AudioFileIndex.IO){files=new AudioFileIndex(this);try{owned=files.ownedPaths();}finally{files.close();}for(java.util.Iterator<MetadataStore.LocalEntry> it=snapshot.iterator();it.hasNext();)if(owned.contains(it.next().song.localPath))it.remove();store.replaceLocalSongs(snapshot);}
            for(MetadataStore.LocalEntry e:snapshot)if(!unchanged.contains(e) || !new io.onloopio.db.GainStore(store).hasLocal(e.song.id,new File(e.song.localPath)))try{new io.onloopio.db.GainStore(store).saveLocal(e.song.id,new File(e.song.localPath),io.onloopio.player.LocalGainTags.read(new File(e.song.localPath)));}catch(java.io.IOException optionalTags){}
            if(config!=null){AudioCache cache=new AudioCache(this,config);new io.onloopio.db.GenerationStore(store).reconcile(config.accountKey(),cache.completedNames(),System.currentTimeMillis());}
            prefs.setNumber("local_scan_seconds",(int)(System.currentTimeMillis()/1000));prefs.setNumber("local_scan_count",snapshot.size());prefs.setNumber("music_migrated",prefs.number("music_migrated",0)+moved);status="Music library updated";
        }catch(Exception unavailable){status="Scan incomplete · previous library retained";android.util.Log.w("OnLoopio","LOCAL_LIBRARY_SCAN_FAILED",unavailable);}
        finally{store.close();running=false;if(wake.isHeld())wake.release();if(notify)sendBroadcast(new Intent(UPDATED).setPackage(getPackageName()));boolean retry;synchronized(MusicLibraryService.class){queued=false;retry=again;again=false;}if(retry)request(this,true);}
    }
}
