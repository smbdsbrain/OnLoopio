package io.onloopio;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.DeviceSettings;
import io.onloopio.library.MusicLibraryService;
import io.onloopio.player.PlaybackService;
import io.onloopio.sync.PlaylistSyncService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import static junit.framework.Assert.*;

/** Explicit resource diagnostics; ordinary test discovery cannot pause a configured player. */
public final class NetworkDeviceProbe extends Instrumentation {
    private String operation;
    private int hours=8;
    private volatile Activity settingsActivity;
    private final StringBuilder report=new StringBuilder();
    public void onCreate(Bundle args){super.onCreate(args);operation=args.getString("operation");if(args.containsKey("hours"))hours=Integer.parseInt(args.getString("hours"));start();}
    public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof io.onloopio.ui.SettingsActivity)settingsActivity=activity;}
    public void onStart(){Bundle result=new Bundle();try{
        if("snapshot".equals(operation))snapshot();else if("export".equals(operation))export();
        else if("baseline".equals(operation))stress(true);else if("fixed".equals(operation))stress(false);
        else if("status".equals(operation))sample("status");else if("restore".equals(operation))restore();
        else if("soak".equals(operation))soak();
        else if("resume".equals(operation)){for(int n=0;n<6;n++)resumeHome();sample("resume-complete");}
        else throw new IllegalArgumentException("Explicit network operation required");
        result.putString("stream",report.toString()+"Network probe "+operation+" passed\n");finish(android.app.Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream",report.toString()+"Network probe failed: "+failure.getClass().getSimpleName()+"\n"+(failure instanceof AssertionError?failure.getMessage():""));finish(android.app.Activity.RESULT_CANCELED,result);}}
    private File stateFile(){return new File(getTargetContext().getFilesDir(),"network-probe-state.json");}
    private File backupFile(){return new File(getTargetContext().getFilesDir(),"network-probe-backup.zip");}
    private static JSONObject json(File file)throws Exception {FileInputStream in=new FileInputStream(file);try{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}finally{in.close();}}
    private void snapshot()throws Exception {
        Context c=getTargetContext();assertFalse("Unrestored probe exists",stateFile().exists());DeviceSettings settings=new DeviceSettings(c);JSONObject state=new JSONObject();
        for(String flag:new String[]{"force_offline","downloads_paused","playlist_auto_sync"})state.put(flag,settings.flag(flag,"playlist_auto_sync".equals(flag)));
        File hold=new File(c.getFilesDir(),"music-library.hold");state.put("hold",hold.exists());
        MetadataStore store=new MetadataStore(c);try{state.put("catalog",store.catalogCount());state.put("playlists",store.playlists().size());state.put("local",store.localCount());state.put("feedback",store.pendingFeedback(store.accountKey()));}finally{store.close();}
        FileOutputStream saved=new FileOutputStream(stateFile());try{saved.write(state.toString().getBytes("UTF-8"));saved.getFD().sync();}finally{saved.close();}
        settings.setFlag("force_offline",true);settings.setFlag("downloads_paused",true);settings.setFlag("playlist_auto_sync",false);
        if(!hold.exists())assertTrue(hold.createNewFile());
        c.stopService(new Intent(c,PlaybackService.class));c.stopService(new Intent(c,PlaylistSyncService.class));
        long deadline=android.os.SystemClock.elapsedRealtime()+60000;while(MusicLibraryService.running && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
        assertFalse("Music scan still running",MusicLibraryService.running);Thread.sleep(2000);
        ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(backupFile()));try{for(String dir:new String[]{"shared_prefs","databases","files"})archive(zip,new File(c.getApplicationInfo().dataDir,dir),dir);}finally{zip.close();}
        sample("snapshot");report.append("Private snapshot ready; original flags recorded separately\n");
    }
    private void archive(ZipOutputStream zip,File file,String name)throws Exception {
        if(file.equals(backupFile()))return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)archive(zip,child,name+"/"+child.getName());return;}
        if(!file.isFile())return;zip.putNextEntry(new ZipEntry(name));FileInputStream in=new FileInputStream(file);try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}finally{in.close();}zip.closeEntry();
    }
    private void export()throws Exception {
        File target=new File("/data/local/tmp/onloopio-network-backup.zip");assertTrue("Prepare shell-owned export first",target.isFile());assertEquals(0L,target.length());
        FileInputStream in=new FileInputStream(backupFile());FileOutputStream out=new FileOutputStream(target);try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}finally{in.close();out.close();}assertTrue(backupFile().delete());
    }
    private void restore()throws Exception {
        JSONObject state=json(stateFile());DeviceSettings settings=new DeviceSettings(getTargetContext());
        for(String flag:new String[]{"force_offline","downloads_paused","playlist_auto_sync"})settings.setFlag(flag,state.getBoolean(flag));
        File hold=new File(getTargetContext().getFilesDir(),"music-library.hold");if(!state.getBoolean("hold") && hold.exists())assertTrue(hold.delete());
        MetadataStore store=new MetadataStore(getTargetContext());try{assertEquals(state.getInt("catalog"),store.catalogCount());assertEquals(state.getInt("playlists"),store.playlists().size());assertEquals(state.getInt("local"),store.localCount());}finally{store.close();}
        assertTrue(stateFile().delete());if(backupFile().exists())assertTrue(backupFile().delete());sample("restored");
    }
    private int descriptors(){String[] files=new File("/proc/self/fd").list();assertNotNull("Cannot count process descriptors",files);return files.length;}
    private void progress(String label)throws Exception {JSONObject stats=sample(label);Bundle update=new Bundle();update.putString("stream",stats.toString()+"\n");sendStatus(0,update);}
    private void resumeHome()throws Exception {
        final Context context=getTargetContext();android.os.PowerManager power=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);
        android.os.PowerManager.WakeLock screen=power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK|android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,"OnLoopio:soak-resume");screen.acquire(10000);
        try{
            context.startActivity(new Intent(context,io.onloopio.ui.SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1000);
            runOnMainSync(new Runnable(){public void run(){Activity activity=settingsActivity;settingsActivity=null;if(activity!=null && !activity.isFinishing())activity.finish();}});
            context.startActivity(new Intent(context,io.onloopio.ui.PlaylistActivity.class).setAction("io.onloopio.OPEN_PLAYER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1000);
            runOnMainSync(new Runnable(){public void run(){assertNotNull("HOME failed to resume",io.onloopio.ui.PlaylistActivity.foreground);}});
        }finally{if(screen.isHeld())screen.release();}
    }
    private void soak()throws Exception {
        assertFalse("Restore preferences before endurance run",stateFile().exists());assertTrue("Invalid duration",hours>=1 && hours<=24);
        final Context context=getTargetContext();final android.os.PowerManager power=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);
        android.os.PowerManager.WakeLock cpu=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"OnLoopio:network-soak");cpu.acquire((hours+1)*3600000L);
        try{
            context.startActivity(new Intent(context,io.onloopio.ui.PlaylistActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(30000);
            ServerConfig config=new io.onloopio.config.ConfigStore(context).load();assertNotNull("Configured server required",config);
            assertFalse("Endurance requires online mode",new DeviceSettings(context).flag("force_offline",false));
            int before=descriptors(),completed=0;long started=android.os.SystemClock.elapsedRealtime(),until=started+hours*3600000L;progress("soak-start");
            while(android.os.SystemClock.elapsedRealtime()<until){
                new NavidromeClient(config).ping();completed++;
                MetadataStore store=new MetadataStore(context);try{assertFalse("Audio index unexpectedly incomplete",store.needsAudioIndex());store.pendingDownloads();store.pendingFeedback(store.accountKey());}finally{store.close();}
                if(completed%10==0){
                    // Exercise actual Activity pause/resume rather than invoking lifecycle methods directly.
                    resumeHome();
                    progress("soak-requests-"+completed);assertTrue("Resources keep accumulating",descriptors()<=before+64);
                }
                Thread.sleep(Math.min(30000,Math.max(1,until-android.os.SystemClock.elapsedRealtime())));
            }
            JSONObject after=sample("soak-complete");assertTrue("Endurance descriptor growth",after.getInt("fd")<=before+16);
            report.append("durationMs=").append(android.os.SystemClock.elapsedRealtime()-started).append(" pings=").append(completed).append(" baselineFd=").append(before).append('\n');
        }finally{if(cpu.isHeld())cpu.release();}
    }
    private JSONObject sample(String label)throws Exception {
        JSONObject stats=new JSONObject();stats.put("label",label);stats.put("fd",descriptors());stats.put("threads",new File("/proc/self/task").list().length);stats.put("pssKb",Debug.getPss());stats.put("nativeBytes",Debug.getNativeHeapAllocatedSize());stats.put("javaBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        Set<String> sockets=new HashSet<String>(),waiting=new HashSet<String>();for(String path:new String[]{"/proc/self/net/tcp","/proc/self/net/tcp6"}){
            BufferedReader in=new BufferedReader(new FileReader(path));try{String line;while((line=in.readLine())!=null){String[] fields=line.trim().split("\\s+");if(fields.length>9 && Integer.toString(Process.myUid()).equals(fields[7])){sockets.add(fields[9]);if("08".equals(fields[3]))waiting.add(fields[9]);}}}finally{in.close();}}
        stats.put("uidTcp",sockets.size());stats.put("uidCloseWait",waiting.size());report.append(stats.toString()).append('\n');return stats;
    }
    private void stress(boolean baseline)throws Exception {
        assertTrue("Snapshot must precede stress",stateFile().exists());JSONObject material=json(new File("/data/local/tmp/onloopio-crash-fixture.json"));
        TlsKeepAliveFixture fixture=new TlsKeepAliveFixture(material);int before=descriptors(),peak=before,done=0;boolean reproduced=false;
        sample("before");try{
            ServerConfig config=fixture.config();NavidromeClient reused=new NavidromeClient(config);
            int limit=baseline?200:1200;for(int n=0;n<limit;n++){
                (n%2==0?reused:new NavidromeClient(config)).ping();done++;int current=descriptors();peak=Math.max(peak,current);
                if(done%50==0)progress("requests-"+done);
                if(baseline && current-before>=128){reproduced=true;break;}
                assertTrue("Descriptor safety limit reached",current<700);
            }
            if(baseline)assertTrue("Original client did not reproduce descriptor accumulation",reproduced);
            else {
                assertEquals(1200,done);assertEquals("All completed requests must opt out of pooling",fixture.requests,fixture.closeRequests);
                failurePaths(config,fixture);
            }
        }finally{fixture.close();}
        Thread.sleep(3000);JSONObject after=sample("after");report.append("requests=").append(done).append(" peakFd=").append(peak).append(" deltaFd=").append(after.getInt("fd")-before).append('\n');
        if(!baseline)assertTrue("Descriptors retained after completed/failed requests",after.getInt("fd")<=before+16);
    }
    private void failurePaths(final ServerConfig config,TlsKeepAliveFixture fixture)throws Exception {
        final NavidromeClient client=new NavidromeClient(config);File partial=new File(getTargetContext().getCacheDir(),"network-probe.part");
        try{
            assertEquals(64,client.cover("fixture").length);client.download("fixture",partial,8*1024*1024,null);assertEquals(64L,partial.length());assertTrue(partial.delete());
            try{client.download("error",partial,8*1024*1024,null);fail("HTTP error accepted");}catch(IOException expected){}
            try{client.download("truncated",partial,8*1024*1024,null);fail("Truncated response accepted");}catch(IOException expected){}
            try{client.download("slow",partial,8*1024*1024,new NavidromeClient.DownloadProgress(){public void bytes(long received,long total)throws IOException{throw new IOException("Fixture cancellation");}});fail("Cancellation ignored");}catch(IOException expected){}
            try{new NavidromeClient(new ServerConfig(config.baseUrl,"fixture","fixture")).ping();fail("Untrusted TLS accepted");}catch(javax.net.ssl.SSLException expected){}
            for(int n=0;n<30;n++){HttpURLConnection stream=client.stream("fixture",null);InputStream input=null;try{input=stream.getInputStream();while(input.read()!=-1){}}finally{if(input!=null)input.close();stream.disconnect();}}
            for(int n=0;n<20;n++){
                io.onloopio.player.StreamProxy proxy=new io.onloopio.player.StreamProxy(config);HttpURLConnection local=(HttpURLConnection)new java.net.URL(proxy.url("slow")).openConnection();InputStream input=null;
                try{local.setReadTimeout(5000);input=local.getInputStream();assertTrue(input.read()>=0);long started=android.os.SystemClock.elapsedRealtime();proxy.close();assertTrue("Stopping proxy blocked the caller",android.os.SystemClock.elapsedRealtime()-started<2000);}
                finally{proxy.close();if(input!=null)input.close();local.disconnect();}
            }
            Thread.sleep(1000);assertEquals("Fixture still has live requests",0,fixture.active());report.append("HTTP errors, truncation, cancellation, TLS rejection and streams passed\n");
        }finally{partial.delete();}
    }
}
