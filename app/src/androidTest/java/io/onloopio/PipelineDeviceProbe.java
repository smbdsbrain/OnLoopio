package io.onloopio;

import android.app.Instrumentation;
import android.os.Bundle;
import android.test.RenamingDelegatingContext;
import io.onloopio.api.*;
import io.onloopio.db.*;
import io.onloopio.model.*;
import io.onloopio.player.*;
import io.onloopio.sync.*;
import java.io.*;
import java.util.*;
import static junit.framework.Assert.*;

/** Real API17 TLS, parser, SQLite and decoder; all endpoints are disposable loopback fixtures. */
public final class PipelineDeviceProbe extends Instrumentation {
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private byte[] read(File file)throws IOException{if(file.length()>262144)throw new IOException("Fixture limit");FileInputStream in=new FileInputStream(file);try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}finally{in.close();}}
    public void onStart(){Bundle result=new Bundle();try{assertEquals("io.onloopio.validation",getTargetContext().getPackageName());run();result.putString("stream","Pipeline TLS/JSON, independent catalog failure, poison isolation, durable Range + native decoder PASS\n");finish(-1,result);}catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));finish(0,result);}}
    private void run()throws Exception{
        final byte[] audio=read(new File("/data/local/tmp/onloopio-audio-fixture/reference.wav"));final int[] catalogs={0},ranges={0};
        final TlsKeepAliveFixture fixture=new TlsKeepAliveFixture(new org.json.JSONObject(new String(read(new File("/data/local/tmp/onloopio-crash-fixture.json")),"UTF-8")));
        String prefix="pipeline_"+System.nanoTime()+"_";MetadataStore store=new MetadataStore(new RenamingDelegatingContext(getTargetContext(),prefix));File partial=new File(getTargetContext().getFilesDir(),prefix+"resume.wav");
        try{
            fixture.handler=new TlsKeepAliveFixture.Handler(){public TlsKeepAliveFixture.Reply respond(String path,Map<String,String> headers)throws Exception{
                if(path.contains("/download.view")){int start=0;if(headers.containsKey("range")){start=Integer.parseInt(headers.get("range").substring(6).replace("-",""));assertEquals("\"fixture-v1\"",headers.get("if-range"));ranges[0]++;}String range=start>0?"Content-Range: bytes "+start+"-"+(audio.length-1)+"/"+audio.length+"\r\n":"";return new TlsKeepAliveFixture.Reply(start>0?206:200,"audio/wav","ETag: \"fixture-v1\"\r\n"+range,Arrays.copyOfRange(audio,start,audio.length));}
                if(path.contains("/getSong.view")){assertTrue(path.contains("f=json"));return new TlsKeepAliveFixture.Reply(200,"application/json","","{\"subsonic-response\":{\"status\":\"ok\",\"song\":{\"id\":\"A\",\"replayGain\":{\"trackGain\":-6.0,\"trackPeak\":1}}}}".getBytes("UTF-8"));}
                if(path.contains("/search3.view")){catalogs[0]++;return new TlsKeepAliveFixture.Reply(503,"application/xml","",new byte[0]);}
                String body="";
                if(path.contains("/getPlaylists.view"))body="<playlists><playlist id='mix' name='Mix' changed='v1' songCount='2' duration='1200'/></playlists>";
                else if(path.contains("/getPlaylist.view"))body="<playlist id='mix' name='Mix' changed='v1' songCount='2' duration='1200'><entry id='A' title='A' artist='Fixture' album='Album' suffix='wav' duration='600'/><entry id='B' title='B' artist='Fixture' album='Album' suffix='wav' duration='600'/></playlist>";
                else if(path.contains("/getStarred2.view"))body="<starred2/>";
                else if(path.contains("/scrobble.view") && path.contains("id=poison"))return xml("<subsonic-response status='failed'><error code='70' message='Missing synthetic song'/></subsonic-response>");
                return xml("<subsonic-response status='ok' version='1.16.1'>"+body+"</subsonic-response>");
            }};
            final ServerConfig config=fixture.config();final NavidromeClient client=new NavidromeClient(config);store.selectAccount(config.accountKey());client.ping();assertEquals(-6.0,client.replayGain("A").track,0.001);
            try{new NavidromeClient(new ServerConfig(config.baseUrl,"fixture","fixture","")).ping();fail("Untrusted loopback certificate accepted");}catch(IOException expected){}
            PlaylistSyncEngine.Source source=new PlaylistSyncEngine.Source(){public void guard(){}public List<Playlist> playlists()throws IOException{return client.getPlaylists();}public PlaylistDetail detail(String id)throws IOException{return client.getPlaylist(id);}public Library catalog()throws IOException{return client.catalog();}};
            PlaylistSyncEngine engine=new PlaylistSyncEngine();assertEquals(1,engine.check(store,config.accountKey(),source,Collections.<String>emptyList(),100,false,Collections.<String>emptySet()).refreshed);assertEquals(0,catalogs[0]);assertTrue(engine.check(store,config.accountKey(),source,Collections.<String>emptyList(),200,true,Collections.<String>emptySet()).catalogFailed);assertEquals(2,store.detail("mix").songs.size());
            store.recordListening(new ListenEvent("bad",config.accountKey(),"poison",100),200);store.recordListening(new ListenEvent("good",config.accountKey(),"A",101),201);
            BacksyncEngine.Result sent=new BacksyncEngine().check(store,config.accountKey(),new BacksyncEngine.Source(){public void guard(){}public void like(String id,boolean liked)throws IOException{if(liked)client.star(id);else client.unstar(id);}public void scrobble(List<ListenEvent> events)throws IOException{client.scrobble(events);}public List<Song> starred()throws IOException{return client.getStarred2();}});assertEquals(1,sent.listens);assertEquals(2,new FeedbackStore(store).history(config.accountKey(),0).size());assertEquals(1,new FeedbackStore(store).held(config.accountKey()).size());
            final ResumableDownload.State state=new ResumableDownload.State();final PartialStore journal=new PartialStore(store);ResumableDownload.Journal durable=new ResumableDownload.Journal(){public void save(ResumableDownload.State value){journal.save(config.accountKey(),"fixture",value);}};
            try{client.downloadVariant("A",AudioProfile.ORIGINAL,partial,262144,state,durable,new NavidromeClient.DownloadProgress(){public void bytes(long count,long total)throws IOException{if(count>=65536)throw new IOException("Synthetic interruption");}});fail("Expected interruption");}catch(IOException expected){}
            ResumableDownload.State restored=journal.load(config.accountKey(),"fixture");assertTrue(restored.offset>0);assertEquals(partial.length(),restored.offset);client.downloadVariant("A",AudioProfile.ORIGINAL,partial,262144,restored,durable,null);assertTrue(ranges[0]>0);assertTrue(Arrays.equals(audio,read(partial)));
            android.media.MediaPlayer decoder=new android.media.MediaPlayer();try{decoder.setDataSource(partial.getPath());decoder.prepare();assertTrue(decoder.getDuration()>=1900);}finally{decoder.release();}
            int previousCatalogs=catalogs[0];independentService(fixture,config);assertEquals(previousCatalogs,catalogs[0]);
        }finally{partial.delete();store.close();getTargetContext().deleteDatabase(prefix+"onloopio.db");fixture.close();}
    }
    private void independentService(TlsKeepAliveFixture fixture,ServerConfig config)throws Exception{
        android.content.Context context=getTargetContext();io.onloopio.config.ConfigStore settings=new io.onloopio.config.ConfigStore(context);assertNull("Disposable clone must have no real account",settings.load());android.content.SharedPreferences device=context.getSharedPreferences("device",0),server=context.getSharedPreferences("server",0);Map<String,?> beforeDevice=new HashMap<String,Object>(device.getAll()),beforeServer=new HashMap<String,Object>(server.getAll());MetadataStore store=new MetadataStore(context);String beforeAccount=store.accountKey();
        try{
            context.stopService(new android.content.Intent(context,PlaybackService.class));Thread.sleep(500);android.net.wifi.WifiInfo wifi=((android.net.wifi.WifiManager)context.getSystemService(android.content.Context.WIFI_SERVICE)).getConnectionInfo();assertNotNull("Connect test Y1 to Wi-Fi",wifi);io.onloopio.device.DeviceSettings prefs=new io.onloopio.device.DeviceSettings(context);prefs.setText("home_ssid",wifi.getSSID());prefs.setFlag("force_offline",false);prefs.setFlag("downloads_paused",true);prefs.setFlag("playlist_auto_sync",false);prefs.setFlag("feedback_auto_sync",true);assertTrue("Connected home Wi-Fi required",new io.onloopio.device.OnlineMode(context).homeWifi());settings.save(config);store.selectAccount(config.accountKey());store.recordListening(new ListenEvent("service-good",config.accountKey(),"A",100),200);
            PlaylistSyncService.requestFeedback(context);PlaylistSyncService.request(context,"startup",false,null);long end=android.os.SystemClock.elapsedRealtime()+15000;while(store.pendingFeedback(config.accountKey())!=0 && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(100);assertEquals("Feedback depends on playlist toggle",0,store.pendingFeedback(config.accountKey()));assertEquals(0,store.playlists().size());assertEquals(0,store.pendingDownloads());
            end=android.os.SystemClock.elapsedRealtime()+5000;while((PlaylistSyncService.busy || PlaylistSyncService.activeWorkers.get()>0) && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(100);assertFalse(PlaylistSyncService.busy);assertEquals(0,PlaylistSyncService.activeWorkers.get());
            prefs.setFlag("force_offline",true);store.recordListening(new ListenEvent("service-blocked",config.accountKey(),"A",101),201);int requests=fixture.requests;context.startService(new android.content.Intent(context,PlaylistSyncService.class).putExtra("reason","manual").putExtra("backsync_only",true));Thread.sleep(2500);assertEquals("Forced offline performed HTTP",requests,fixture.requests);assertEquals(1,store.pendingFeedback(config.accountKey()));
        }finally{context.stopService(new android.content.Intent(context,PlaylistSyncService.class));Thread.sleep(500);long end=android.os.SystemClock.elapsedRealtime()+5000;while(PlaylistSyncService.activeWorkers.get()>0 && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(100);for(String table:new String[]{"listen_event","listen_history","attempt_ledger"})store.getWritableDatabase().delete(table,table.equals("listen_event")?"session_id LIKE 'service-%'":"attempt_id LIKE 'service-%'",null);store.selectAccount(beforeAccount==null?"":beforeAccount);store.close();restore(device,beforeDevice);restore(server,beforeServer);SyncScheduler.ensure(context,false);}
    }
    private static void restore(android.content.SharedPreferences prefs,Map<String,?> values){android.content.SharedPreferences.Editor editor=prefs.edit().clear();for(Map.Entry<String,?> e:values.entrySet()){Object v=e.getValue();if(v instanceof Boolean)editor.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)editor.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)editor.putLong(e.getKey(),(Long)v);else if(v instanceof String)editor.putString(e.getKey(),(String)v);else if(v instanceof Float)editor.putFloat(e.getKey(),(Float)v);}assertTrue(editor.commit());}
    private static TlsKeepAliveFixture.Reply xml(String body)throws IOException{return new TlsKeepAliveFixture.Reply(200,"application/xml","",body.getBytes("UTF-8"));}
}
