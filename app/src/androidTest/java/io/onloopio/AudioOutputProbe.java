package io.onloopio;

import android.app.Instrumentation;
import android.os.Bundle;
import android.media.MediaPlayer;
import android.media.AudioManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit synthetic AUX probe. It never opens server configuration or private music. */
public final class AudioOutputProbe extends Instrumentation {
    private String mode,format;
    public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode");format=args.getString("format","wav");start();}
    public void onStart(){Bundle result=new Bundle();AudioManager audio=(AudioManager)getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE);int volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);int code=-1,initialFds=fds();
        try{
            if(!"io.onloopio.validation".equals(getTargetContext().getPackageName()))throw new IllegalStateException("Disposable package required");
            if(!format.matches("wav|flac|mp3"))throw new IllegalArgumentException("Unsupported fixture format");
            File fixtures=new File(getTargetContext().getFilesDir(),"audio-validation");fixtures.mkdirs();
            for(String name:new String[]{"reference."+format,"first."+format,"second."+format}){File source=new File("/data/local/tmp/onloopio-audio-fixture",name);if(source.length()>400000)throw new IllegalStateException("Fixture size limit");FileInputStream in=new FileInputStream(source);try{FileOutputStream out=new FileOutputStream(new File(fixtures,name));try{byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}finally{out.close();}}finally{in.close();}}
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.min(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),3),0);
            audio.requestAudioFocus(null,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
            Thread.sleep(1500);
            if(mode.startsWith("service-")){servicePair();result.putString("stream","AUX "+mode+" "+format+" PASS\n");}
            else{
            int count="pair".equals(mode)?50:mode.startsWith("gain")?10:1;
            for(int n=0;n<count;n++){
                play(false,mode.startsWith("gain")?new io.onloopio.player.ReplayGain(0.0,null,null,null,null,null).volume(1,0,0,false):1);Thread.sleep(400);
                if("pair".equals(mode))play(true,1);
                else if(mode.startsWith("gain"))play(false,new io.onloopio.player.ReplayGain("gain-positive".equals(mode)?6.0:-6.0,null,1.0,null,null,null).volume(1,0,0,false));
                Thread.sleep(400);
            }
            result.putString("stream","AUX "+mode+" "+format+" PASS loops="+count+" volume="+Math.min(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),3)+"\n");
            }
            int finalFds=fds();result.putString("stream",result.getString("stream")+"FD before="+initialFds+" after="+finalFds+" delta="+(finalFds-initialFds)+"\n");if(finalFds-initialFds>16)throw new IllegalStateException("Decoder resource growth");
        }catch(Throwable failed){result.putString("stream",android.util.Log.getStackTraceString(failed));code=0;}
        finally{audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);audio.abandonAudioFocus(null);}
        finish(code,result);
    }
    private int fds(){String[] paths=new File("/proc/self/fd").list();if(paths==null)throw new IllegalStateException("FD counter unavailable");return paths.length;}
    private void servicePair()throws Exception{
        final boolean gain=mode.startsWith("service-gain");final int expected=gain?20:150;final android.content.Context context=getTargetContext();io.onloopio.device.DeviceSettings prefs=new io.onloopio.device.DeviceSettings(context);android.content.SharedPreferences raw=context.getSharedPreferences("device",0);java.util.Map<String,?> before=new java.util.HashMap<String,Object>(raw.getAll());
        io.onloopio.db.MetadataStore metadata=new io.onloopio.db.MetadataStore(context);java.util.List<io.onloopio.db.MetadataStore.LocalEntry> originals=metadata.localEntries();File root=new File(io.onloopio.library.MusicPaths.root(),"OnLoopio-AUX-"+System.nanoTime());root.mkdirs();
        try{
            context.stopService(new android.content.Intent(context,io.onloopio.player.PlaybackService.class));Thread.sleep(500);new io.onloopio.db.SessionStore(metadata).clear();
            for(String key:new String[]{"force_offline","downloads_paused","gapless_experimental"})prefs.setFlag(key,true);for(String key:new String[]{"playlist_auto_sync","feedback_auto_sync","shuffle","idle_shutdown","cache_auto_clean"})prefs.setFlag(key,false);prefs.setNumber("repeat",0);prefs.setNumber("replay_gain",gain?1:0);prefs.setNumber("eq_preset","service-gain-eq".equals(mode)?0:-1);
            java.util.List<io.onloopio.model.Song> three=new java.util.ArrayList<io.onloopio.model.Song>();java.util.List<io.onloopio.db.MetadataStore.LocalEntry> local=new java.util.ArrayList<io.onloopio.db.MetadataStore.LocalEntry>(originals);
            for(String name:gain?new String[]{"reference","first"}:new String[]{"reference","first","second"}){File file=new File(root,name+"."+format);FileInputStream input=new FileInputStream(new File(context.getFilesDir(),"audio-validation/"+(gain?"reference":name)+"."+format));FileOutputStream output=new FileOutputStream(file);try{byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}finally{input.close();output.close();}io.onloopio.model.Song song=new io.onloopio.model.Song("local:aux:"+name,"AUX "+name,"Synthetic","Fixture",format,gain || "reference".equals(name)?2:1,"",0,"","",0,"",file.getCanonicalPath());if(gain)new io.onloopio.db.GainStore(metadata).saveLocal(song.id,file,new io.onloopio.player.ReplayGain("reference".equals(name)?0.0:"service-gain-negative".equals(mode)?-6.0:6.0,null,1.0,null,null,null));three.add(song);local.add(new io.onloopio.db.MetadataStore.LocalEntry(song,file.length(),file.lastModified()));}
            metadata.replaceLocalSongs(local);java.util.List<io.onloopio.model.Song> queue=new java.util.ArrayList<io.onloopio.model.Song>();for(int n=0;n<(gain?10:50);n++)queue.addAll(three);io.onloopio.player.PlaybackService.play(context,queue,0,false);
            int position=0;long deadline=android.os.SystemClock.elapsedRealtime()+240000;while(android.os.SystemClock.elapsedRealtime()<deadline){io.onloopio.player.PlaybackService.State state=io.onloopio.player.PlaybackService.state;if(state.queuePosition<position)throw new IllegalStateException("Queue moved backwards");position=state.queuePosition;if(position==expected && !state.playing && "End of queue".equals(state.message))break;Thread.sleep(50);}
            if(position!=expected || io.onloopio.player.PlaybackService.state.playing)throw new IllegalStateException("Incomplete service queue: "+position+" "+io.onloopio.player.PlaybackService.state.message);
            long until=android.os.SystemClock.elapsedRealtime()+5000;while(io.onloopio.player.PlaybackService.checkpoints.get()>0 && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(20);
            long attempts=android.database.DatabaseUtils.longForQuery(metadata.getReadableDatabase(),"SELECT COUNT(*) FROM listen_history WHERE song_id LIKE 'local:aux:%'",null);if(attempts!=expected)throw new IllegalStateException("Attempt accounting: "+attempts);if(metadata.pendingFeedback(metadata.accountKey())!=0)throw new IllegalStateException("Short local fixtures entered outbox");
        }finally{
            context.stopService(new android.content.Intent(context,io.onloopio.player.PlaybackService.class));Thread.sleep(500);long until=android.os.SystemClock.elapsedRealtime()+5000;while(io.onloopio.player.PlaybackService.checkpoints.get()>0 && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(20);new io.onloopio.db.SessionStore(metadata).clear();metadata.replaceLocalSongs(originals);metadata.getWritableDatabase().delete("listen_history","song_id LIKE 'local:aux:%'",null);metadata.getWritableDatabase().delete("attempt_ledger","song_id LIKE 'local:aux:%'",null);metadata.getWritableDatabase().delete("replay_gain","account='' AND song_id LIKE 'local:aux:%'",null);metadata.close();for(String name:new String[]{"reference","first","second"})new File(root,name+"."+format).delete();root.delete();android.content.SharedPreferences.Editor editor=raw.edit().clear();for(java.util.Map.Entry<String,?> e:before.entrySet()){Object v=e.getValue();if(v instanceof Boolean)editor.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)editor.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)editor.putLong(e.getKey(),(Long)v);else if(v instanceof String)editor.putString(e.getKey(),(String)v);else if(v instanceof Float)editor.putFloat(e.getKey(),(Float)v);}editor.commit();
        }
    }
    private void play(final boolean pair,final float volume)throws Exception{
        final MediaPlayer[] owned=new MediaPlayer[2];final CountDownLatch done=new CountDownLatch(1);final String[] error={null};
        try{runOnMainSync(new Runnable(){public void run(){try{
            File root=new File(getTargetContext().getFilesDir(),"audio-validation");MediaPlayer first=new MediaPlayer();owned[0]=first;
            first.setDataSource(new File(root,pair?"first."+format:"reference."+format).getPath());first.setVolume(volume,volume);first.prepare();
            MediaPlayer last=first;if(pair){last=new MediaPlayer();owned[1]=last;last.setDataSource(new File(root,"second."+format).getPath());last.setVolume(volume,volume);last.prepare();first.setNextMediaPlayer(last);}
            last.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){done.countDown();}});
            MediaPlayer.OnErrorListener errors=new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){error[0]=what+":"+extra;done.countDown();return true;}};first.setOnErrorListener(errors);last.setOnErrorListener(errors);first.start();
        }catch(Exception failed){error[0]=failed.getClass().getSimpleName();done.countDown();}}});
        if(!done.await(6,TimeUnit.SECONDS))throw new IllegalStateException("Decoder transition timeout");if(error[0]!=null)throw new IllegalStateException(error[0]);
        }finally{runOnMainSync(new Runnable(){public void run(){for(MediaPlayer p:owned)if(p!=null)p.release();}});}
    }
}
