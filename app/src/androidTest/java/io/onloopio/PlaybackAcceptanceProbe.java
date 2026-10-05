package io.onloopio;

import android.app.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioManager;
import android.os.*;
import io.onloopio.db.*;
import io.onloopio.device.DeviceSettings;
import io.onloopio.library.MusicPaths;
import io.onloopio.model.Song;
import io.onloopio.player.*;
import java.io.*;
import java.util.*;

/** Explicit, reversible local playback qualification; no server writes or removable-media tests. */
public final class PlaybackAcceptanceProbe extends Instrumentation {
    private String operation;private int minutes;
    private Context context;private MetadataStore store;private DeviceSettings prefs;private AudioManager audio;private File root;private Activity settingsActivity;
    public void onCreate(Bundle args){super.onCreate(args);operation=args.getString("operation");minutes=Integer.parseInt(args.getString("minutes","120"));start();}
    public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof io.onloopio.ui.SettingsActivity)settingsActivity=activity;}
    public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{context=getTargetContext();if(!Arrays.asList("io.onloopio","io.onloopio.validation").contains(context.getPackageName()))throw new IllegalStateException("Unexpected target");store=new MetadataStore(context);prefs=new DeviceSettings(context);audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);root=MusicPaths.resolve(MusicPaths.root(),"OnLoopio-acceptance-fixture");
            if("cleanup".equals(operation))cleanup();else{if(!"long-focus".equals(operation) && !"soak".equals(operation))throw new IllegalArgumentException("Explicit operation required");if(minutes<1 || minutes>240)throw new IllegalArgumentException("Duration 1..240 minutes");prepare();try{if("long-focus".equals(operation))longFocus();else soak();}finally{cleanup();}}
            result.putString("stream","Playback acceptance "+operation+" PASS\n");
        }catch(Throwable failed){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(failed));}
        finally{if(store!=null)store.close();}finish(code,result);
    }
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private SharedPreferences backup(){return context.getSharedPreferences("acceptance-probe-device",0);}
    private SharedPreferences marker(){return context.getSharedPreferences("acceptance-probe-state",0);}
    private void copy(SharedPreferences from,SharedPreferences to){SharedPreferences.Editor e=to.edit().clear();for(Map.Entry<String,?> entry:from.getAll().entrySet()){String k=entry.getKey();Object v=entry.getValue();if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof String)e.putString(k,(String)v);else throw new IllegalStateException("Unexpected preference type");}check(e.commit(),"Preferences commit");}
    private void stopped()throws Exception{context.stopService(new Intent(context,PlaybackService.class));Thread.sleep(700);long end=SystemClock.elapsedRealtime()+5000;while(PlaybackService.checkpoints.get()!=0 && SystemClock.elapsedRealtime()<end)Thread.sleep(50);check(PlaybackService.checkpoints.get()==0,"Checkpoints did not drain");}
    private void prepare()throws Exception{
        check(!marker().getBoolean("prepared",false),"Run cleanup for interrupted probe");check(!root.exists(),"Fixture directory already exists");stopped();copy(context.getSharedPreferences("device",0),backup());boolean hold=new File(context.getFilesDir(),"music-library.hold").exists();check(marker().edit().putBoolean("prepared",true).putBoolean("hold",hold).putInt("volume",audio.getStreamVolume(AudioManager.STREAM_MUSIC)).commit(),"Probe marker");
        new File(context.getFilesDir(),"music-library.hold").createNewFile();for(String flag:new String[]{"force_offline","downloads_paused"})prefs.setFlag(flag,true);for(String flag:new String[]{"playlist_auto_sync","feedback_auto_sync","shuffle","idle_shutdown","cache_auto_clean","controls_locked"})prefs.setFlag(flag,false);prefs.setFlag("gapless_experimental",true);prefs.setNumber("repeat",0);prefs.setNumber("replay_gain",0);prefs.setNumber("eq_preset",-1);audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.min(3,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),0);
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{for(String table:new String[]{"local_song","playback_session","queue_entry"})db.execSQL("CREATE TABLE acceptance_saved_"+table+" AS SELECT * FROM "+table);db.setTransactionSuccessful();}finally{db.endTransaction();}new SessionStore(store).clear();check(root.mkdirs(),"Fixture directory");
    }
    private boolean table(String name){return DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",new String[]{name})!=0;}
    private void cleanup()throws Exception{
        if(!marker().getBoolean("prepared",false))return;stopped();SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{for(String name:new String[]{"local_song","playback_session","queue_entry"})if(table("acceptance_saved_"+name)){db.delete(name,null,null);db.execSQL("INSERT INTO "+name+" SELECT * FROM acceptance_saved_"+name);db.execSQL("DROP TABLE acceptance_saved_"+name);}for(String name:new String[]{"listen_history","attempt_ledger","audio_state","track_like"})db.delete(name,"song_id LIKE 'local:acceptance:%'",null);db.setTransactionSuccessful();}finally{db.endTransaction();}
        copy(backup(),context.getSharedPreferences("device",0));audio.setStreamVolume(AudioManager.STREAM_MUSIC,marker().getInt("volume",0),0);if(!marker().getBoolean("hold",false))new File(context.getFilesDir(),"music-library.hold").delete();
        for(String name:new String[]{"long-vbr.mp3","long.flac","a.wav","b.wav"}){File f=new File(root,name);check(!f.exists() || f.delete(),"Fixture cleanup "+name);}check(!root.exists() || root.delete(),"Fixture directory cleanup");backup().edit().clear().commit();marker().edit().clear().commit();
    }
    private Song imported(String name,int seconds)throws Exception{
        File source=new File("/data/local/tmp/onloopio-long-fixture",name),target=new File(root,name);check(source.length()>16 && source.length()<=256L*1024*1024,"Bounded long fixture required");FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target);try{byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}finally{in.close();out.close();}return song(name,seconds,target);
    }
    private Song shortWave(String name,int seconds)throws Exception{File file=new File(root,name);DataOutputStream out=new DataOutputStream(new FileOutputStream(file));try{int bytes=32000*seconds;out.writeBytes("RIFF");le(out,bytes+36,4);out.writeBytes("WAVEfmt ");le(out,16,4);le(out,1,2);le(out,1,2);le(out,16000,4);le(out,32000,4);le(out,2,2);le(out,16,2);out.writeBytes("data");le(out,bytes,4);byte[] silence=new byte[32000];for(int n=0;n<seconds;n++)out.write(silence);}finally{out.close();}return song(name,seconds,file);}
    private void le(DataOutputStream out,int v,int n)throws Exception{for(int k=0;k<n;k++)out.writeByte(v>>(8*k));}
    private Song song(String name,int seconds,File file)throws Exception{return new Song("local:acceptance:"+name,name,"Synthetic","Acceptance",AudioCache.audioExtension(file,""),seconds,"",0,"","",0,"",file.getCanonicalPath());}
    private void play(List<Song> queue)throws Exception{List<MetadataStore.LocalEntry> entries=store.localEntries();Set<String> ids=new HashSet<String>();for(MetadataStore.LocalEntry entry:entries)ids.add(entry.song.id);for(Song s:queue)if(ids.add(s.id)){File f=new File(s.localPath);entries.add(new MetadataStore.LocalEntry(s,f.length(),f.lastModified()));}store.replaceLocalSongs(entries);PlaybackService.play(context,queue,0,false);await(true);}
    private void await(boolean playing)throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(PlaybackService.state.song!=null && PlaybackService.state.playing==playing && PlaybackService.state.duration>0)return;Thread.sleep(50);}throw new AssertionError("Playback state playing="+playing+" "+PlaybackService.state.message);}
    private void seek(int seconds)throws Exception{context.startService(new Intent(context,PlaybackService.class).setAction(PlaybackService.SEEK).putExtra("seconds",seconds-PlaybackService.state.position/1000));long end=SystemClock.elapsedRealtime()+10000;while(Math.abs(PlaybackService.state.position-seconds*1000)>2000 && SystemClock.elapsedRealtime()<end)Thread.sleep(100);check(Math.abs(PlaybackService.state.position-seconds*1000)<=2000,"Long seek position "+PlaybackService.state.position);}
    private void focus(boolean explicitPause,int kind)throws Exception{AudioManager.OnAudioFocusChangeListener other=new AudioManager.OnAudioFocusChangeListener(){public void onAudioFocusChange(int change){}};check(audio.requestAudioFocus(other,AudioManager.STREAM_MUSIC,kind)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED,"Focus grant");try{await(false);if(explicitPause)PlaybackService.action(context,PlaybackService.PAUSE);Thread.sleep(200);}finally{audio.abandonAudioFocus(other);}if(explicitPause || kind==AudioManager.AUDIOFOCUS_GAIN){Thread.sleep(700);check(!PlaybackService.state.playing,"Unexpected focus autoplay");PlaybackService.action(context,PlaybackService.RESUME);await(true);}else await(true);}
    private void longFocus()throws Exception{for(String name:new String[]{"long-vbr.mp3","long.flac"}){Song song=imported(name,7200);play(Arrays.asList(song,song));check(PlaybackService.state.duration>=7100000,"Long native duration");seek(3600);focus(false,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);focus(true,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);focus(false,AudioManager.AUDIOFOCUS_GAIN);PlaybackService.action(context,PlaybackService.PAUSE);await(false);seek(6900);Thread.sleep(1000);check(!PlaybackService.state.playing,"Paused long seek autoplay");PlaybackService.action(context,PlaybackService.RESUME);await(true);Thread.sleep(2000);check(Math.abs(PlaybackService.state.position-6902000)<4000,"Long seek changed after resume: "+PlaybackService.state.position);sample("long-focus-"+song.suffix,0);stopped();}}
    private int count(String path){String[] files=new File(path).list();check(files!=null,"Resource counter");return files.length;}
    private int[] sample(String phase,long seconds){Debug.MemoryInfo m=new Debug.MemoryInfo();Debug.getMemoryInfo(m);int[] r={count("/proc/self/fd"),count("/proc/self/task"),m.getTotalPss()};Bundle b=new Bundle();b.putString("stream","phase="+phase+" elapsed_s="+seconds+" fd="+r[0]+" threads="+r[1]+" pss_kb="+r[2]+" native_bytes="+Debug.getNativeHeapAllocatedSize()+" position_ms="+PlaybackService.state.position+" queue="+PlaybackService.state.queuePosition+" checkpoints="+PlaybackService.checkpoints.get()+"\n");sendStatus(0,b);return r;}
    private void navigate()throws Exception{context.startActivity(new Intent(context,io.onloopio.ui.SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500);runOnMainSync(new Runnable(){public void run(){if(settingsActivity!=null){settingsActivity.finish();settingsActivity=null;}}});context.startActivity(new Intent(context,io.onloopio.ui.PlaylistActivity.class).setAction("io.onloopio.OPEN_PLAYER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500);}
    private void soak()throws Exception{
        Song a=shortWave("a.wav",31),b=shortWave("b.wav",37);List<Song> queue=new ArrayList<Song>();for(int n=0;n<120;n++){queue.add(a);queue.add(b);}prefs.setNumber("repeat",2);play(queue);long started=SystemClock.elapsedRealtime(),until=started+minutes*60000L,nextSample=started,nextControl=started+120000,nextNavigation=started+300000;int[] warm=null;int controls=0;
        while(SystemClock.elapsedRealtime()<until){long now=SystemClock.elapsedRealtime();if(!PlaybackService.state.playing)await(true);check(PlaybackService.state.song!=null && PlaybackService.state.song.id.startsWith("local:acceptance:"),"Unexpected playback owner");
            if(now>=nextControl){focus(controls%2==0,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);PlaybackService.action(context,PlaybackService.PAUSE);await(false);Thread.sleep(100);PlaybackService.action(context,PlaybackService.RESUME);await(true);controls++;nextControl=now+120000;}
            if(now>=nextNavigation){navigate();nextNavigation=now+300000;}
            if(now>=nextSample){int[] r=sample("soak",(now-started)/1000);if(now-started>=600000){if(warm==null)warm=r;check(r[0]<=warm[0]+16,"FD growth after warmup");check(r[1]<=warm[1]+12,"Thread growth after warmup");check(r[2]<=warm[2]+32768,"PSS growth after warmup");}nextSample=now+60000;}
            Thread.sleep(500);
        }
        sample("soak-complete",(SystemClock.elapsedRealtime()-started)/1000);check(controls>=minutes/3,"Control cycles missing");check(DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM listen_history WHERE song_id LIKE 'local:acceptance:%'",null)>=minutes,"Listen attempts missing");check(DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM listen_event WHERE song_id LIKE 'local:acceptance:%'",null)==0,"Local fixtures entered outbox");
    }
}
