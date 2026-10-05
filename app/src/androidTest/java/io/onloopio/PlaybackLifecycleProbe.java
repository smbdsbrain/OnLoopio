package io.onloopio;
import android.app.Instrumentation;
import android.os.Bundle;
import android.content.*;
import io.onloopio.db.*;
import io.onloopio.player.*;
import io.onloopio.model.*;
import io.onloopio.device.*;
import java.io.*;
import java.util.*;
import static junit.framework.Assert.*;
/** Explicit synthetic probe; the host kills the process between prepare and restore operations. */
public final class PlaybackLifecycleProbe extends Instrumentation {
    private String operation;public void onCreate(Bundle args){super.onCreate(args);operation=args.getString("operation");start();}
    public void onStart(){Bundle result=new Bundle();try{assertEquals("io.onloopio.validation",getTargetContext().getPackageName());run();result.putString("stream","Lifecycle "+operation+" PASS\n");finish(-1,result);}catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));finish(0,result);}}
    private void waitFor(boolean playing)throws Exception{long end=android.os.SystemClock.elapsedRealtime()+12000;while(android.os.SystemClock.elapsedRealtime()<end){if(PlaybackService.state.song!=null && PlaybackService.state.playing==playing && PlaybackService.state.duration>0)return;Thread.sleep(100);}assertTrue(PlaybackService.state.message,PlaybackService.state.song!=null && PlaybackService.state.playing==playing && PlaybackService.state.duration>0);}
    private File fixture(){return new File(io.onloopio.library.MusicPaths.root(),"OnLoopio-validation/long.wav");}
    private void generate()throws Exception{File f=fixture();f.getParentFile().mkdirs();DataOutputStream out=new DataOutputStream(new FileOutputStream(f));try{int bytes=16000*2*90;out.writeBytes("RIFF");le(out,36+bytes,4);out.writeBytes("WAVEfmt ");le(out,16,4);le(out,1,2);le(out,1,2);le(out,16000,4);le(out,32000,4);le(out,2,2);le(out,16,2);out.writeBytes("data");le(out,bytes,4);out.write(new byte[bytes]);}finally{out.close();}}
    private void le(DataOutputStream out,int v,int n)throws IOException{for(int b=0;b<n;b++)out.writeByte(v>>(8*b));}
    private void run()throws Exception{
        Context c=getTargetContext();DeviceSettings prefs=new DeviceSettings(c);for(String key:new String[]{"force_offline","downloads_paused"})prefs.setFlag(key,true);for(String key:new String[]{"playlist_auto_sync","feedback_auto_sync","shuffle"})prefs.setFlag(key,false);prefs.setNumber("repeat",0);ControlLock.locked(c,false);MetadataStore store=new MetadataStore(c);
        try{
            if("prepare".equals(operation)){
                c.stopService(new Intent(c,PlaybackService.class));Thread.sleep(400);new SessionStore(store).clear();generate();Song s=new Song("local:validation","Validation","Synthetic","Fixture","wav",90,"",0,"","",0,"",fixture().getCanonicalPath());store.replaceLocalSongs(Arrays.asList(new MetadataStore.LocalEntry(s,fixture().length(),fixture().lastModified())));PlaybackService.play(c,Arrays.asList(s,s),0,false);waitFor(true);Thread.sleep(1000);c.startService(new Intent(c,PlaybackService.class).setAction(PlaybackService.SEEK).putExtra("seconds",15));Thread.sleep(500);PlaybackService.action(c,PlaybackService.PAUSE);waitFor(false);Thread.sleep(500);assertTrue(PlaybackService.state.position>=14000);SessionStore.Restored saved=new SessionStore(store).load();assertNotNull(saved);assertEquals(2,saved.queue.entries().size());assertTrue(saved.position>=14000);
            }else if("restore".equals(operation)){
                SessionStore.Restored before=new SessionStore(store).load();assertNotNull(before);PlaybackService.action(c,PlaybackService.KICK);waitFor(false);assertEquals(2,PlaybackService.state.queueSize);assertTrue(Math.abs(before.position-PlaybackService.state.position)<1500);Thread.sleep(2000);assertFalse(PlaybackService.state.playing);assertEquals(before.attempt,new SessionStore(store).load().attempt);
            }else if("pause-race".equals(operation)){
                Song s=store.song("local:validation");PlaybackService.play(c,Arrays.asList(s,s),0,false);PlaybackService.action(c,PlaybackService.PAUSE);Thread.sleep(2000);assertFalse("Late prepare started sound",PlaybackService.state.playing);PlaybackService.action(c,PlaybackService.QUIESCE);Thread.sleep(500);assertNotNull(new SessionStore(store).load());
            }else if("usb-denied".equals(operation)){
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,c.checkCallingOrSelfPermission("android.permission.MOUNT_UNMOUNT_FILESYSTEMS"));SessionStore.Restored before=new SessionStore(store).load();assertNotNull(before);try{UsbStorage.enable(c);fail("Missing mount privilege");}catch(SecurityException expected){}assertFalse(prefs.flag("usb_quiescing",false));assertEquals(before.attempt,new SessionStore(store).load().attempt);
            }else if("usb".equals(operation)){
                UsbStorage.enable(c);long end=android.os.SystemClock.elapsedRealtime()+12000;while(!UsbStorage.enabled(c) && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(100);boolean enabled=UsbStorage.enabled(c);if(!enabled){UsbStorage.mounted(c);fail("USB enable: "+io.onloopio.library.MusicLibraryService.status+" checkpoints="+PlaybackService.checkpoints.get());}assertFalse(PlaybackService.state.playing);UsbStorage.disable(c);Thread.sleep(3000);UsbStorage.mounted(c);PlaybackService.action(c,PlaybackService.KICK);waitFor(false);assertNotNull(new SessionStore(store).load());
            }else if("cleanup".equals(operation)){c.stopService(new Intent(c,PlaybackService.class));Thread.sleep(500);new SessionStore(store).clear();fixture().delete();fixture().getParentFile().delete();}
            else throw new IllegalArgumentException("Explicit operation required");
        }finally{store.close();}
    }
}
