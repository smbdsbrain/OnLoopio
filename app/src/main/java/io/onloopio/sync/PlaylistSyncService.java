package io.onloopio.sync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import io.onloopio.config.ConfigStore;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.DeviceSettings;
import io.onloopio.device.OnlineMode;
import io.onloopio.model.Library;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Song;
import io.onloopio.model.ListenEvent;
import io.onloopio.player.AudioCache;
import io.onloopio.player.PlaybackService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A bounded worker independent of Activity lifetime; metadata checks do not interrupt playback. */
public final class PlaylistSyncService extends Service {
    public static final String UPDATED="io.onloopio.PLAYLISTS_UPDATED";
    public static final String FEEDBACK_UPDATED="io.onloopio.FEEDBACK_UPDATED";
    public static volatile boolean busy;
    private final Handler main=new Handler();private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<String> reasons=new LinkedHashSet<String>(),forced=new HashSet<String>();private boolean full,running,metadataRequested;private volatile boolean destroyed;
    private DeviceSettings settings;private MetadataStore store;private int latestStart;
    private PowerManager.WakeLock wake;private WifiManager.WifiLock wifi;
    public static void request(Context c,String reason,boolean full,String playlist){c.startService(new Intent(c,PlaylistSyncService.class).putExtra("reason",reason).putExtra("full",full).putExtra("playlist",playlist));}
    public static void requestFeedback(Context c){
        DeviceSettings prefs=new DeviceSettings(c);
        if(prefs.flag("playlist_auto_sync",true) && !prefs.flag("force_offline",false) && new OnlineMode(c).homeWifi())
            c.startService(new Intent(c,PlaylistSyncService.class).putExtra("reason","feedback").putExtra("backsync_only",true));
    }
    public static void feedbackChanged(Context c){c.sendBroadcast(new Intent(FEEDBACK_UPDATED).setPackage(c.getPackageName()));requestFeedback(c);}
    public void onCreate(){super.onCreate();settings=new DeviceSettings(this);store=new MetadataStore(this);}
    public int onStartCommand(Intent intent,int flags,int id){
        latestStart=id;if(intent==null)return START_NOT_STICKY;String reason=intent.getStringExtra("reason");if(reason==null)reason="manual";
        // A rejected automatic trigger must not cancel a manual check still in its debounce window.
        if(!"manual".equals(reason) && !settings.flag("playlist_auto_sync",true)){SyncReceiver.releaseHandoff();if(!running && reasons.isEmpty())stopSelf(id);return START_NOT_STICKY;}
        holdCpu();SyncReceiver.releaseHandoff();
        reasons.add(reason);metadataRequested|=!intent.getBooleanExtra("backsync_only",false);full|=intent.getBooleanExtra("full",false);String playlist=intent.getStringExtra("playlist");if(playlist!=null)forced.add(playlist);
        if(!running){main.removeCallbacks(dispatch);main.postDelayed(dispatch,1500);}return START_NOT_STICKY;
    }
    private final Runnable dispatch=new Runnable(){public void run(){
        if(destroyed || running || reasons.isEmpty())return;holdCpu();running=true;busy=true;final boolean complete=full,manual=reasons.contains("manual"),metadata=metadataRequested;
        final Set<String> force=new HashSet<String>(forced);final String trigger=join(reasons);reasons.clear();forced.clear();full=false;metadataRequested=false;
        settings.setText("sync_last_trigger",trigger);settings.setText("sync_status",metadata?"Synchronizing Navidrome…":"Sending likes and listens…");
        worker.submit(new Runnable(){public void run(){runCheck(complete,manual,metadata,force,trigger);}});
    }};
    private void runCheck(boolean full,final boolean manual,boolean metadata,Set<String> force,String trigger){
        String message;boolean reachable=false,metadataSuccess=!metadata,feedbackSuccess=false;final long started=android.os.SystemClock.elapsedRealtime();
        ServerConfig selected=null;
        try{
            final ServerConfig config=new ConfigStore(this).load();final OnlineMode mode=new OnlineMode(this);
            if(config==null)throw new IOException("Configure server over USB");
            selected=config;
            final NavidromeClient client=new NavidromeClient(config);
            PlaylistSyncEngine.Source source=new PlaylistSyncEngine.Source(){
                public void guard()throws IOException{
                    if(destroyed || Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime()-started>240000)throw new IOException("Check interrupted");
                    if(!manual && !settings.flag("playlist_auto_sync",true))throw new IOException("Automatic sync disabled");
                    if(settings.flag("force_offline",false))throw new IOException("Forced offline");
                    if(!mode.homeWifi())throw new IOException("Waiting for home Wi-Fi");
                    ServerConfig current=new ConfigStore(PlaylistSyncService.this).load();if(current==null || !current.accountKey().equals(config.accountKey()))throw new IOException("Account changed");
                }
                public List<Playlist> playlists()throws IOException{return client.getPlaylists();}
                public PlaylistDetail detail(String id)throws IOException{return client.getPlaylist(id);}
                public Library catalog()throws IOException{return client.catalog(new NavidromeClient.Check(){public void check()throws IOException{guard();}});}
            };
            source.guard();acquireWifi();client.ping();source.guard();reachable=true;store.selectAccount(config.accountKey());
            String feedback;
            try{
                BacksyncEngine.Result sent=new BacksyncEngine().check(store,config.accountKey(),new BacksyncEngine.Source(){
                    public void guard()throws IOException{source.guard();}
                    public void like(String id,boolean liked)throws IOException{if(liked)client.star(id);else client.unstar(id);}
                    public void scrobble(List<ListenEvent> events)throws IOException{client.scrobble(events);}
                    public List<Song> starred()throws IOException{return client.getStarred2();}
                });
                feedbackSuccess=sent.success();feedback=sent.likes+" likes · "+sent.listens+" listens sent"+(feedbackSuccess?"":" · feedback incomplete");
            }catch(Exception failed){feedback="Feedback pending";android.util.Log.w("OnLoopio","BACKSYNC_FAILED reason="+failed.getClass().getSimpleName());}
            message=feedback;
            if(metadata){
                try{
                    source.guard();List<String> completed;try{completed=new AudioCache(this,config).completedNames();}catch(IOException unavailable){completed=new ArrayList<String>();}
                    PlaylistSyncEngine.Result result=new PlaylistSyncEngine().check(store,config.accountKey(),source,completed,System.currentTimeMillis(),full,force);
                    message=result.playlists+" playlists · "+result.refreshed+" refreshed · "+result.queued+" queued\n"+feedback;metadataSuccess=true;
                    android.util.Log.i("OnLoopio","PLAYLIST_SYNC_OK trigger="+trigger+" refreshed="+result.refreshed+" queued="+result.queued);
                }catch(Exception failed){message="Playlist check incomplete; previous playlists kept\n"+feedback;android.util.Log.w("OnLoopio","PLAYLIST_SYNC_FAILED reason="+failed.getClass().getSimpleName());}
            }
        }catch(Exception failure){
            message=settings.flag("force_offline",false)?"Forced offline":!new OnlineMode(this).homeWifi()?"Waiting for home Wi-Fi":"Navidrome unavailable; previous playlists kept";
            android.util.Log.w("OnLoopio","PLAYLIST_SYNC_SKIPPED trigger="+trigger+" reason="+failure.getClass().getSimpleName());
        }finally{
            releaseWifi();ServerConfig current=new ConfigStore(this).load();
            if(!destroyed && selected!=null && current!=null && current.accountKey().equals(selected.accountKey()) && !settings.flag("force_offline",false) && new OnlineMode(this).homeWifi() && store.pendingDownloads()>0)PlaybackService.action(this,PlaybackService.KICK);
        }
        if(destroyed){store.close();return;}
        if(selected!=null)message+=" · "+store.pendingFeedback(selected.accountKey())+" pending";
        if(settings.flag("force_offline",false))message="Forced offline";
        else if(!new OnlineMode(this).homeWifi())message="Waiting for home Wi-Fi";
        final String result=message;final boolean ok=metadataSuccess && feedbackSuccess,connected=reachable;
        main.post(new Runnable(){public void run(){if(destroyed)return;settings.setText("sync_status",result);running=false;busy=false;
            sendBroadcast(new Intent(UPDATED).setPackage(getPackageName()).putExtra("success",ok).putExtra("reachable",connected));
            sendBroadcast(new Intent(FEEDBACK_UPDATED).setPackage(getPackageName()));
            if(reasons.isEmpty())stopSelf(latestStart);else {holdCpu();main.postDelayed(dispatch,1500);}
        }});
    }
    private synchronized void holdCpu(){if(wake==null){PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OnLoopio:playlist-sync");wake.setReferenceCounted(false);}wake.acquire(300000);}
    private synchronized void acquireWifi(){wifi=((WifiManager)getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL,"OnLoopio:playlist-sync");wifi.setReferenceCounted(false);wifi.acquire();}
    private synchronized void releaseLocks(){if(wifi!=null && wifi.isHeld())wifi.release();if(wake!=null && wake.isHeld())wake.release();}
    private synchronized void releaseWifi(){if(wifi!=null && wifi.isHeld())wifi.release();}
    private static String join(Set<String> reasons){StringBuilder text=new StringBuilder();for(String reason:reasons){if(text.length()>0)text.append(", ");text.append(reason);}return text.toString();}
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){destroyed=true;busy=false;main.removeCallbacksAndMessages(null);worker.shutdownNow();releaseLocks();store.close();super.onDestroy();}
}
