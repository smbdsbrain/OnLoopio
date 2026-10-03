package io.onloopio;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.KeyEvent;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import io.onloopio.config.ConfigStore;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.ControlLock;
import io.onloopio.device.DeviceSettings;
import io.onloopio.device.OnlineMode;
import io.onloopio.model.ListenEvent;
import io.onloopio.model.Song;
import io.onloopio.player.AudioCache;
import io.onloopio.player.MediaButtons;
import io.onloopio.player.PlaybackService;
import io.onloopio.sync.PlaylistSyncService;
import io.onloopio.sync.SyncScheduler;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import static junit.framework.Assert.*;

/** Explicit hardware probe: ordinary test discovery cannot submit live scrobbles. */
public final class FeedbackDeviceProbe extends Instrumentation {
    private String operation;
    public void onCreate(Bundle args){super.onCreate(args);operation=args.getString("operation");start();}
    public void onStart(){Bundle result=new Bundle();try{
        if("snapshot".equals(operation))snapshot();else if("export".equals(operation))export();else if("offline".equals(operation))offline();
        else if("sync".equals(operation))sync();else if("remote".equals(operation))remote();
        else if("native".equals(operation))nativeButtons();else if("manual-race".equals(operation))manualRace();else if("restore".equals(operation))restore();else throw new IllegalArgumentException("Explicit feedback operation required");
        result.putString("stream","Feedback device "+operation+" passed\n");finish(android.app.Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));finish(android.app.Activity.RESULT_CANCELED,result);}}
    private File stateFile(){return new File(getTargetContext().getFilesDir(),"feedback-device-state.json");}
    private JSONObject state()throws Exception {FileInputStream in=new FileInputStream(stateFile());try{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);return new JSONObject(bytes.toString("UTF-8"));}finally{in.close();}}
    private void save(JSONObject state)throws Exception {FileOutputStream out=new FileOutputStream(stateFile());try{out.write(state.toString().getBytes("UTF-8"));}finally{out.close();}}
    private void snapshot()throws Exception {
        Context c=getTargetContext();assertFalse("Unrestored hardware probe exists",stateFile().exists());DeviceSettings prefs=new DeviceSettings(c);
        JSONObject state=new JSONObject();for(String key:new String[]{"force_offline","downloads_paused","playlist_auto_sync","controls_locked"})state.put(key,prefs.flag(key,"playlist_auto_sync".equals(key)));
        state.put("volume",((AudioManager)c.getSystemService(Context.AUDIO_SERVICE)).getStreamVolume(AudioManager.STREAM_MUSIC));save(state);
        prefs.setFlag("playlist_auto_sync",false);prefs.setFlag("downloads_paused",true);prefs.setFlag("force_offline",true);ControlLock.locked(c,false);
        c.stopService(new Intent(c,PlaybackService.class));c.stopService(new Intent(c,PlaylistSyncService.class));Thread.sleep(2000);
        ServerConfig config=new ConfigStore(c).load();assertNotNull("Configured Navidrome required",config);MetadataStore store=new MetadataStore(c);
        try{assertEquals(config.accountKey(),store.accountKey());assertEquals("Existing feedback must finish before this probe",0,store.pendingFeedback(config.accountKey()));
            AudioCache cache=new AudioCache(c,config);Song selected=null;for(Song s:store.offlineSongs(cache.completedNames()))if(s.duration>65 && ("mp3".equals(s.suffix)||"flac".equals(s.suffix))){selected=s;break;}
            assertNotNull("A downloaded MP3/FLAC over 65 seconds is required",selected);state.put("song",selected.id);state.put("liked",store.isLiked(selected.id));state.put("plays",store.audioState(selected.id).plays);save(state);
        }finally{store.close();}
        File backup=new File(c.getExternalFilesDir(null),"feedback-device-backup.zip");ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(backup));
        try{for(String directory:new String[]{"shared_prefs","databases","files"}){File root=new File(c.getApplicationInfo().dataDir,directory);File[] files=root.listFiles();if(files==null)continue;for(File file:files){if(!file.isFile())continue;zip.putNextEntry(new ZipEntry(directory+"/"+file.getName()));FileInputStream in=new FileInputStream(file);try{byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)zip.write(bytes,0,n);}finally{in.close();}zip.closeEntry();}}}finally{zip.close();}
        android.util.Log.i("OnLoopioTest","FEEDBACK_BACKUP_READY");
    }
    private void export()throws Exception {
        File source=new File(getTargetContext().getExternalFilesDir(null),"feedback-device-backup.zip");
        File target=new File("/data/local/tmp/onloopio-feedback-device.zip");assertTrue(source.isFile());assertTrue("ADB must prepare the export file",target.isFile());assertEquals(0L,target.length());
        FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target);try{byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);out.getFD().sync();}finally{in.close();out.close();}assertTrue(source.delete());
    }
    private void tap()throws Exception {Context c=getTargetContext();long now=android.os.SystemClock.uptimeMillis();MediaButtons.handle(c,new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0));MediaButtons.handle(c,new KeyEvent(now,now+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0));}
    private void offline()throws Exception {
        Context c=getTargetContext();JSONObject original=state();DeviceSettings prefs=new DeviceSettings(c);prefs.setFlag("force_offline",true);prefs.setFlag("downloads_paused",true);prefs.setFlag("playlist_auto_sync",false);
        MetadataStore store=new MetadataStore(c);try{Song song=store.song(original.getString("song"));assertNotNull(song);String account=store.accountKey();int before=store.audioState(song.id).plays;
            ((AudioManager)c.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_MUSIC,0,0);PlaybackService.play(c,Collections.singletonList(song),0,false);
            for(int n=0;n<150 && !(PlaybackService.state.playing && PlaybackService.state.song!=null && song.id.equals(PlaybackService.state.song.id));n++)Thread.sleep(100);assertTrue(PlaybackService.state.message,PlaybackService.state.playing);
            tap();Thread.sleep(100);tap();Thread.sleep(600);assertEquals(!original.getBoolean("liked"),store.isLiked(song.id));assertTrue("Double Play paused playback",PlaybackService.state.playing);
            tap();Thread.sleep(650);assertFalse(PlaybackService.state.playing);int position=PlaybackService.state.position;Thread.sleep(1300);assertTrue(Math.abs(PlaybackService.state.position-position)<150);
            tap();Thread.sleep(650);assertTrue(PlaybackService.state.playing);
            long until=android.os.SystemClock.elapsedRealtime()+40000;while(store.audioState(song.id).plays==before && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(250);
            assertEquals(before+1,store.audioState(song.id).plays);List<ListenEvent> events=store.pendingListens(account,25);assertEquals(1,events.size());assertEquals(song.id,events.get(0).songId);assertTrue(events.get(0).time<System.currentTimeMillis()-29000);
            assertEquals(2,store.pendingFeedback(account));PlaylistSyncService.request(c,"manual",false,null);Thread.sleep(2600);assertEquals("Forced offline sent feedback",2,store.pendingFeedback(account));
            original.put("session",events.get(0).sessionId);original.put("time",events.get(0).time);save(original);
        }finally{PlaybackService.action(c,PlaybackService.STOP);Thread.sleep(600);store.close();}
    }
    private void sync()throws Exception {
        Context c=getTargetContext();JSONObject original=state();DeviceSettings prefs=new DeviceSettings(c);MetadataStore store=new MetadataStore(c);
        try{String account=store.accountKey();List<ListenEvent> events=store.pendingListens(account,25);assertEquals("Listen did not survive process restart",1,events.size());assertEquals(original.getString("session"),events.get(0).sessionId);assertEquals(original.getLong("time"),events.get(0).time);assertEquals(2,store.pendingFeedback(account));
            prefs.setFlag("force_offline",false);prefs.setFlag("downloads_paused",true);assertTrue("Home Wi-Fi unavailable",new OnlineMode(c).homeWifi());long previous=store.lastPlaylistCheck();
            final Context target=c;runOnMainSync(new Runnable(){public void run(){android.content.ComponentName started=target.startService(new Intent(target,PlaylistSyncService.class).putExtra("reason","manual"));android.util.Log.i("OnLoopioTest","FEEDBACK_REQUESTED component="+(started==null?"null":started.getClassName())+" forced="+new DeviceSettings(target).flag("force_offline",true));}});
            long deadline=android.os.SystemClock.elapsedRealtime()+120000;while((store.pendingFeedback(account)!=0 || store.lastPlaylistCheck()<=previous || PlaylistSyncService.busy) && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(250);
            assertEquals(prefs.text("sync_status",""),0,store.pendingFeedback(account));assertTrue("Playlist routine not executed",store.lastPlaylistCheck()>previous);assertTrue(prefs.flag("downloads_paused",false));
            assertEquals(!original.getBoolean("liked"),remoteLiked(new NavidromeClient(new ConfigStore(c).load()),original.getString("song")));
            Thread.sleep(2500);assertEquals(0,store.pendingFeedback(account));android.util.Log.i("OnLoopioTest","FEEDBACK_LIVE_SYNC_CONFIRMED");
        }finally{store.close();}
    }
    private boolean remoteLiked(NavidromeClient client,String id)throws Exception {for(Song s:client.getStarred2())if(id.equals(s.id))return true;return false;}
    private void remote()throws Exception {
        Context c=getTargetContext();JSONObject original=state();String id=original.getString("song");boolean liked=original.getBoolean("liked");NavidromeClient client=new NavidromeClient(new ConfigStore(c).load());if(liked)client.star(id);else client.unstar(id);
        MetadataStore store=new MetadataStore(c);try{assertEquals(!liked,store.isLiked(id));PlaylistSyncService.requestFeedback(c); // Auto sync is disabled: explicitly request a guarded feedback-only pass.
            c.startService(new Intent(c,PlaylistSyncService.class).putExtra("reason","manual").putExtra("backsync_only",true));long deadline=android.os.SystemClock.elapsedRealtime()+60000;
            while((store.isLiked(id)!=liked || PlaylistSyncService.busy) && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(250);assertEquals("Remote edit not imported",liked,store.isLiked(id));assertEquals(0,store.pendingFeedback(store.accountKey()));
        }finally{store.close();}
    }
    private void restore()throws Exception {
        Context c=getTargetContext();JSONObject original=state();c.stopService(new Intent(c,PlaybackService.class));c.stopService(new Intent(c,PlaylistSyncService.class));Thread.sleep(1000);DeviceSettings prefs=new DeviceSettings(c);
        if(original.has("song")){String id=original.getString("song");boolean liked=original.getBoolean("liked");NavidromeClient client=new NavidromeClient(new ConfigStore(c).load());if(liked)client.star(id);else client.unstar(id);MetadataStore store=new MetadataStore(c);try{if(store.isLiked(id)!=liked)store.toggleLike(store.accountKey(),id);for(io.onloopio.model.LikeChange change:store.pendingLikes(store.accountKey(),1000))if(id.equals(change.songId))store.acknowledgeLike(change);store.applyStarredSnapshot(store.accountKey(),client.getStarred2());}finally{store.close();}}
        for(String key:new String[]{"force_offline","downloads_paused","playlist_auto_sync"})prefs.setFlag(key,original.getBoolean(key));ControlLock.locked(c,original.getBoolean("controls_locked"));((AudioManager)c.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_MUSIC,original.getInt("volume"),0);SyncScheduler.ensure(c,true);
        File backup=new File(c.getExternalFilesDir(null),"feedback-device-backup.zip");if(backup.exists())assertTrue(backup.delete());assertTrue(stateFile().delete());
    }
    private void nativeButtons()throws Exception {
        Context c=getTargetContext();DeviceSettings prefs=new DeviceSettings(c);prefs.setFlag("force_offline",true);prefs.setFlag("playlist_auto_sync",false);MetadataStore store=new MetadataStore(c);
        try{Song song=store.song(state().getString("song"));assertNotNull(song);boolean liked=store.isLiked(song.id);int plays=store.audioState(song.id).plays;
            ((AudioManager)c.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_MUSIC,0,0);PlaybackService.play(c,Collections.singletonList(song),0,false);
            for(int n=0;n<150 && !PlaybackService.state.playing;n++)Thread.sleep(100);assertTrue(PlaybackService.state.message,PlaybackService.state.playing);PlaybackService.action(c,PlaybackService.PAUSE);Thread.sleep(600);assertFalse(PlaybackService.state.playing);
            android.os.PowerManager power=(android.os.PowerManager)c.getSystemService(Context.POWER_SERVICE);android.util.Log.i("OnLoopioTest","FEEDBACK_NATIVE_READY");
            long deadline=android.os.SystemClock.elapsedRealtime()+20000;while(power.isScreenOn() && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);assertFalse("Host must turn the screen off",power.isScreenOn());android.util.Log.i("OnLoopioTest","FEEDBACK_SCREEN_OFF_READY");
            deadline=android.os.SystemClock.elapsedRealtime()+8000;while(store.isLiked(song.id)==liked && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);Thread.sleep(650);
            assertEquals("Native double Play did not toggle like",!liked,store.isLiked(song.id));assertFalse("Native double Play resumed paused audio",PlaybackService.state.playing);assertEquals("Short native test added a listen",plays,store.audioState(song.id).plays);
        }finally{PlaybackService.action(c,PlaybackService.STOP);Thread.sleep(500);store.close();}
    }
    private void manualRace()throws Exception {
        final Context c=getTargetContext();DeviceSettings prefs=new DeviceSettings(c);boolean auto=prefs.flag("playlist_auto_sync",true),forced=prefs.flag("force_offline",false),paused=prefs.flag("downloads_paused",false);MetadataStore store=new MetadataStore(c);
        try{prefs.setFlag("playlist_auto_sync",false);prefs.setFlag("force_offline",false);prefs.setFlag("downloads_paused",true);c.stopService(new Intent(c,PlaylistSyncService.class));Thread.sleep(1800);long previous=store.lastPlaylistCheck();
            runOnMainSync(new Runnable(){public void run(){PlaylistSyncService.request(c,"manual",false,null);PlaylistSyncService.request(c,"startup",false,null);}});
            long deadline=android.os.SystemClock.elapsedRealtime()+15000;while(store.lastPlaylistCheck()<=previous && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            assertTrue("Disabled startup canceled pending manual sync",store.lastPlaylistCheck()>previous);
        }finally{c.stopService(new Intent(c,PlaylistSyncService.class));prefs.setFlag("playlist_auto_sync",auto);prefs.setFlag("force_offline",forced);prefs.setFlag("downloads_paused",paused);SyncScheduler.ensure(c,true);store.close();}
    }
}
