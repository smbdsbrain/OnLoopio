package io.onloopio;

import android.content.*;
import android.test.InstrumentationTestCase;
import io.onloopio.db.*;
import io.onloopio.device.DeviceSettings;
import io.onloopio.model.Song;
import io.onloopio.player.*;
import java.io.*;
import java.util.*;

/** Decoder callbacks and queue edits against the real service with disposable local silence. */
public final class PlaybackReliabilityTest extends InstrumentationTestCase {
    private Context context;private MetadataStore store;private DeviceSettings prefs;private File root;private List<MetadataStore.LocalEntry> original;private Map<String,?> settings;private int volume;
    protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext();assertEquals("io.onloopio.validation",context.getPackageName());context.stopService(new Intent(context,PlaybackService.class));Thread.sleep(500);store=new MetadataStore(context);original=store.localEntries();settings=new HashMap<String,Object>(context.getSharedPreferences("device",0).getAll());prefs=new DeviceSettings(context);for(String key:new String[]{"force_offline","downloads_paused","gapless_experimental"})prefs.setFlag(key,true);for(String key:new String[]{"shuffle","idle_shutdown","cache_auto_clean","playlist_auto_sync","feedback_auto_sync"})prefs.setFlag(key,false);prefs.setNumber("repeat",0);prefs.setNumber("eq_preset",-1);prefs.setNumber("replay_gain",0);new SessionStore(store).clear();root=new File(io.onloopio.library.MusicPaths.root(),"OnLoopio-reliability-"+System.nanoTime());assertTrue(root.mkdirs());android.media.AudioManager audio=(android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE);volume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,0,0);
    }
    protected void tearDown()throws Exception{
        try{context.stopService(new Intent(context,PlaybackService.class));Thread.sleep(500);waitCheckpoints();new SessionStore(store).clear();store.replaceLocalSongs(original);store.close();File[] files=root.listFiles();if(files!=null)for(File file:files){assertEquals(root.getCanonicalPath(),file.getParentFile().getCanonicalPath());assertTrue(file.delete());}assertTrue(root.delete());android.content.SharedPreferences.Editor editor=context.getSharedPreferences("device",0).edit().clear();for(Map.Entry<String,?> e:settings.entrySet()){Object v=e.getValue();if(v instanceof Boolean)editor.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)editor.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)editor.putLong(e.getKey(),(Long)v);else if(v instanceof String)editor.putString(e.getKey(),(String)v);}assertTrue(editor.commit());((android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(android.media.AudioManager.STREAM_MUSIC,volume,0);}finally{super.tearDown();}
    }
    private void waitCheckpoints()throws Exception{long until=android.os.SystemClock.elapsedRealtime()+5000;while(PlaybackService.checkpoints.get()>0 && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(20);assertEquals(0,PlaybackService.checkpoints.get());}
    private Song song(String name,int seconds,boolean exists)throws Exception{File file=new File(root,name+".wav");if(exists){DataOutputStream out=new DataOutputStream(new FileOutputStream(file));try{int bytes=32000*seconds;out.writeBytes("RIFF");le(out,bytes+36,4);out.writeBytes("WAVEfmt ");le(out,16,4);le(out,1,2);le(out,1,2);le(out,16000,4);le(out,32000,4);le(out,2,2);le(out,16,2);out.writeBytes("data");le(out,bytes,4);out.write(new byte[bytes]);}finally{out.close();}}return new Song("local:reliable:"+name,name,"Synthetic","Fixture","wav",seconds,"",0,"","",0,"",file.getCanonicalPath());}
    private void le(DataOutputStream out,int value,int count)throws IOException{for(int n=0;n<count;n++)out.writeByte(value>>(8*n));}
    private void play(Song... songs)throws Exception{List<MetadataStore.LocalEntry> entries=new ArrayList<MetadataStore.LocalEntry>(original);for(Song song:songs){File file=new File(song.localPath);entries.add(new MetadataStore.LocalEntry(song,file.length(),file.lastModified()));}store.replaceLocalSongs(entries);PlaybackService.play(context,Arrays.asList(songs),0,false);}
    private void await(String id,boolean playing)throws Exception{long until=android.os.SystemClock.elapsedRealtime()+5000;while(android.os.SystemClock.elapsedRealtime()<until){if(PlaybackService.state.song!=null && id.equals(PlaybackService.state.song.id) && PlaybackService.state.playing==playing)return;Thread.sleep(50);}fail("Expected "+id+" playing="+playing+" actual="+(PlaybackService.state.song==null?"null":PlaybackService.state.song.id)+" playing="+PlaybackService.state.playing+" queue="+PlaybackService.state.queuePosition+"/"+PlaybackService.state.queueSize+" "+PlaybackService.state.message);}
    public void testAllBadRepeatAllStopsWithoutLoop()throws Exception{prefs.setNumber("repeat",2);play(song("bad-a",1,false),song("bad-b",1,false),song("bad-c",1,false));long until=android.os.SystemClock.elapsedRealtime()+5000;while(!"No playable tracks".equals(PlaybackService.state.message) && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertEquals("No playable tracks",PlaybackService.state.message);assertFalse(PlaybackService.state.playing);waitCheckpoints();assertNotNull(new SessionStore(store).load());}
    public void testRemoveCurrentPreservesPausedIntent()throws Exception{Song a=song("a",10,true),b=song("b",10,true);play(a,b);await(a.id,true);PlaybackService.action(context,PlaybackService.PAUSE);await(a.id,false);String entry=PlaybackService.playbackQueue.get(0).id;context.startService(new Intent(context,PlaybackService.class).setAction(PlaybackService.QUEUE_REMOVE).putExtra("entry",entry));await(b.id,false);Thread.sleep(1000);assertFalse(PlaybackService.state.playing);assertEquals(1,PlaybackService.state.queueSize);waitCheckpoints();assertEquals(b.id,new SessionStore(store).load().queue.current().song.id);}
    public void testSeekPauseNoisyAndNextEditDoNotAutostart()throws Exception{Song a=song("long",10,true),b=song("next",1,true);play(a,b);await(a.id,true);Thread.sleep(700);context.sendBroadcast(new Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY).setPackage(context.getPackageName()));await(a.id,false);context.startService(new Intent(context,PlaybackService.class).setAction(PlaybackService.SEEK).putExtra("seconds",5));String next=PlaybackService.playbackQueue.get(1).id;context.startService(new Intent(context,PlaybackService.class).setAction(PlaybackService.QUEUE_REMOVE).putExtra("entry",next));Thread.sleep(1500);assertFalse(PlaybackService.state.playing);assertEquals(a.id,PlaybackService.state.song.id);assertEquals(1,PlaybackService.state.queueSize);PlaybackService.action(context,PlaybackService.RESUME);await(a.id,true);}
    public void testRepeatOneCreatesAttemptAndManualNextExits()throws Exception{prefs.setNumber("repeat",1);Song a=song("short",1,true),b=song("manual",10,true);play(a,b);await(a.id,true);waitCheckpoints();String first=new SessionStore(store).load().attempt;Thread.sleep(1400);waitCheckpoints();assertEquals(a.id,PlaybackService.state.song.id);assertFalse(first.equals(new SessionStore(store).load().attempt));PlaybackService.action(context,PlaybackService.NEXT);await(b.id,true);assertEquals(0,store.pendingFeedback(store.accountKey()));}
    public void testActualCompressedArtifactDoesNotUsePreparedNext()throws Exception{
        prefs.setFlag("gapless_experimental",true);
        File compressed=new File(root,"compressed-as-wave.wav");
        InputStream input=getInstrumentation().getContext().getAssets().open("music/fixture.mp3");
        FileOutputStream output=new FileOutputStream(compressed);
        try{byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}finally{input.close();output.close();}
        Song a=new Song("local:reliable:compressed","Compressed source","Synthetic","Fixture","wav",12,"",0,"","",0,"",compressed.getCanonicalPath());
        play(a,song("actual-next",10,true));await(a.id,true);Thread.sleep(700);
        assertEquals("mp3",AudioCache.audioExtension(compressed,""));
        assertNull("Compressed artifact preloaded as WAV",preparedNext());
    }
    private Object preparedNext()throws Exception{
        final Object[] prepared={null};final boolean[] found={false};final String[] error={null};
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{
            // Inspect the running API17 service on its Looper; production exposes no test binder.
            Class<?> thread=Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method current=thread.getDeclaredMethod("currentActivityThread");current.setAccessible(true);
            java.lang.reflect.Field services=thread.getDeclaredField("mServices");services.setAccessible(true);
            for(Object value:((Map<?,?>)services.get(current.invoke(null))).values())if(value instanceof PlaybackService){
                found[0]=true;java.lang.reflect.Field next=PlaybackService.class.getDeclaredField("preparedNext");next.setAccessible(true);prepared[0]=next.get(value);
            }
        }catch(Exception failed){error[0]=failed.getClass().getSimpleName();}}});
        assertNull(error[0]);assertTrue("Running service missing",found[0]);return prepared[0];
    }
    public void testFocusGainCannotUndoExplicitPauseWithPreparedNext()throws Exception{
        Song a=song("focus-current",10,true);play(a,song("focus-next",1,true));await(a.id,true);
        long deadline=android.os.SystemClock.elapsedRealtime()+3000;
        while(preparedNext()==null && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
        assertNotNull("Fixture did not prepare next",preparedNext());
        android.media.AudioManager audio=(android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        android.media.AudioManager.OnAudioFocusChangeListener other=new android.media.AudioManager.OnAudioFocusChangeListener(){public void onAudioFocusChange(int ignored){}};
        try{
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(other,android.media.AudioManager.STREAM_MUSIC,android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT));
            await(a.id,false);PlaybackService.action(context,PlaybackService.PAUSE);Thread.sleep(150);
            audio.abandonAudioFocus(other);Thread.sleep(800);
            assertFalse(PlaybackService.state.playing);assertEquals(a.id,PlaybackService.state.song.id);assertNull(preparedNext());
            PlaybackService.action(context,PlaybackService.RESUME);await(a.id,true);
        }finally{audio.abandonAudioFocus(other);}
    }

}
