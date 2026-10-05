package io.onloopio.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.RemoteControlClient;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import io.onloopio.R;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import io.onloopio.config.ConfigStore;
import io.onloopio.db.MetadataStore;
import io.onloopio.db.SessionStore;
import io.onloopio.device.DeviceSettings;
import io.onloopio.device.OnlineMode;
import io.onloopio.model.Song;
import io.onloopio.model.ListenEvent;
import io.onloopio.sync.PlaylistSyncService;
import io.onloopio.device.ControlLock;
import android.view.KeyEvent;
import io.onloopio.ui.PlaylistActivity;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.media.MediaPlayer;

/** Plays completed offline audio or streams directly; download jobs are independent and persistent. */
public final class PlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String CLEAN="io.onloopio.CLEAN_CACHE",REMOVE_DOWNLOAD="io.onloopio.REMOVE_DOWNLOAD_JOB",RESUME_PENDING="io.onloopio.RESUME_PENDING_DOWNLOADS";
    public static final String MEDIA_KEY="io.onloopio.MEDIA_KEY",TOGGLE_LIKE="io.onloopio.TOGGLE_LIKE";
    public static final String QUEUE_JUMP="io.onloopio.QUEUE_JUMP",QUEUE_REMOVE="io.onloopio.QUEUE_REMOVE",QUEUE_MOVE_NEXT="io.onloopio.QUEUE_MOVE_NEXT",QUEUE_CLEAR_REMAINING="io.onloopio.QUEUE_CLEAR_REMAINING",QUIESCE="io.onloopio.QUIESCE";
    public static final java.util.concurrent.atomic.AtomicInteger checkpoints=new java.util.concurrent.atomic.AtomicInteger();
    public static volatile List<PlaybackQueue.Entry> playbackQueue=Collections.emptyList();
    public static final String PLAY="io.onloopio.PLAY", TOGGLE="io.onloopio.TOGGLE", NEXT="io.onloopio.NEXT",
        PREVIOUS="io.onloopio.PREVIOUS", SEEK="io.onloopio.SEEK", SYNC="io.onloopio.SYNC_AUDIO",
        STOP="io.onloopio.STOP", SETTINGS="io.onloopio.APPLY_DEVICE_SETTINGS", CANCEL="io.onloopio.CANCEL_DOWNLOAD",
        PAUSE="io.onloopio.PAUSE", RESUME="io.onloopio.RESUME", CLEAR="io.onloopio.CLEAR_AUDIO",
        ENQUEUE="io.onloopio.ENQUEUE_DOWNLOAD", REMOVE="io.onloopio.REMOVE_AUDIO",
        APPEND="io.onloopio.APPEND_QUEUE", PLAY_NEXT="io.onloopio.PLAY_NEXT", RETRY="io.onloopio.RETRY_DOWNLOAD",CLEAR_QUEUE="io.onloopio.CLEAR_DOWNLOAD_QUEUE",KICK="io.onloopio.RESUME_QUEUED_DOWNLOADS";
    public static final class State {
        public final Song song; public final String message; public final boolean playing,busy,liked;
        public final int position,duration,queuePosition,queueSize;public final Song next;
        State(Song song,String message,boolean playing,boolean busy,int position,int duration) {
            this(song,message,playing,busy,position,duration,null,0,0);
        }
        State(Song song,String message,boolean playing,boolean busy,int position,int duration,Song next,int qpos,int qsize) {
            this(song,message,playing,busy,position,duration,next,qpos,qsize,false);
        }
        State(Song song,String message,boolean playing,boolean busy,int position,int duration,Song next,int qpos,int qsize,boolean liked) {
            this.song=song; this.message=message; this.playing=playing; this.busy=busy; this.position=position; this.duration=duration;this.next=next;queuePosition=qpos;queueSize=qsize;
            this.liked=liked;
        }
    }
    public static volatile State state=new State(null,"Choose a track to play.",false,false,0,0);
    public static final class DownloadState {
        public final Song song;public final long received,total,speed;public final String phase;
        DownloadState(Song song,long received,long total,long speed,String phase){this.song=song;this.received=received;this.total=total;this.speed=speed;this.phase=phase;}
    }
    public static volatile DownloadState downloads=new DownloadState(null,0,-1,0,"Idle");
    public static volatile String cleanupMessage="";public static volatile Set<String> protectedTracks=Collections.emptySet();
    private volatile Set<String> protectedPlayback=Collections.emptySet();private Song nextSong;private int nextIndex=-1;private boolean cleaning;private long lastCleanup;
    private final ListeningSession listening=new ListeningSession();private final PlayGesture playGesture=new PlayGesture();
    private PlaybackQueue traversal=new PlaybackQueue(new Random());private final RecoveryPass recovery=new RecoveryPass();
    private static final ExecutorService stateWorker=Executors.newSingleThreadExecutor();
    private boolean playIntent,restoring,queueDirty,sessionFinished,sourceNormalized;private int savedPosition;private long lastCheckpoint,lastSavedProgress;
    private String sessionId,playbackAccount;private long sessionStarted,healthyMillis;private boolean seeking,buffering,playbackCompleted;
    private PowerManager.WakeLock gestureWake;
    private final Handler main=new Handler();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(),downloadWorker=Executors.newSingleThreadExecutor();
    private MediaPlayer player; private Equalizer equalizer; private RemoteControlClient remote;
    private File gainFile,preparedGainFile;private String gainToken,preparedGainToken;
    private MediaPlayer preparedNext;private String preparedEntry,preloadAttempt;private boolean preparedNormalized;private float gainVolume=1;private boolean ducked;
    private void invalidateNext(){if(player!=null)try{player.setNextMediaPlayer(null);}catch(RuntimeException ignored){}if(preparedNext!=null){preparedNext.release();preparedNext=null;}preparedEntry=null;}
    private AudioManager audio; private MetadataStore store; private DeviceSettings settings;
    private OnlineMode online; private StreamProxy proxy; private boolean prepared,busy,resumeAfterFocus,transcode,destroyed;
    private int generation,queueRequest;private boolean pendingPlay,queueLoading;private volatile int downloadGeneration;private Song song;private String message="Choose a track to play.";
    private final List<String> queue=new ArrayList<String>(); private int index; private ComponentName buttons;
    private final BroadcastReceiver noisy=new BroadcastReceiver() { public void onReceive(Context c,Intent i) { pause(); } };
    private final BroadcastReceiver lifecycle=new BroadcastReceiver(){public void onReceive(Context c,Intent i){resumeAfterFocus=false;pause();checkpoint(true);generation++;releaseDecoder();downloadGeneration++;if(Intent.ACTION_SHUTDOWN.equals(i.getAction()))stopSelf();else {message="Storage unavailable";publish();}}};
    private final BroadcastReceiver feedbackUpdated=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(ControlLock.CHANGED.equals(i.getAction()))cancelPlayGesture();publish();}};
    public static void action(Context context,String action) { context.startService(new Intent(context,PlaybackService.class).setAction(action)); }
    public static void play(Context context,List<Song> tracks,int selected,boolean sync) {
        ArrayList<String> ids=new ArrayList<String>(); for(Song track:tracks) ids.add(track.id);
        context.startService(new Intent(context,PlaybackService.class).setAction(sync?ENQUEUE:PLAY).putStringArrayListExtra("queue",ids).putExtra("index",selected));
    }
    public static void entityAction(Context c,String action,List<Song> tracks) {
        ArrayList<String> ids=new ArrayList<String>(); for(Song song:tracks) ids.add(song.id);
        c.startService(new Intent(c,PlaybackService.class).setAction(action).putStringArrayListExtra("queue",ids));
    }
    public void onCreate() {
        super.onCreate(); store=new MetadataStore(this); settings=new DeviceSettings(this); online=new OnlineMode(this);
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);createDecoder();
        registerReceiver(lifecycle,new IntentFilter(Intent.ACTION_SHUTDOWN));IntentFilter storage=new IntentFilter();storage.addAction(Intent.ACTION_MEDIA_UNMOUNTED);storage.addAction(Intent.ACTION_MEDIA_SHARED);storage.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);storage.addDataScheme("file");registerReceiver(lifecycle,storage);
        registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        IntentFilter feedbackFilter=new IntentFilter(PlaylistSyncService.FEEDBACK_UPDATED);feedbackFilter.addAction(ControlLock.CHANGED);registerReceiver(feedbackUpdated,feedbackFilter);
        buttons=new ComponentName(this,MediaButtons.class); audio.registerMediaButtonEventReceiver(buttons);
        PendingIntent receiver=PendingIntent.getBroadcast(this,0,new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(buttons),0);
        remote=new RemoteControlClient(receiver); audio.registerRemoteControlClient(remote);
        remote.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE|RemoteControlClient.FLAG_KEY_MEDIA_NEXT|RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
        lastCleanup=android.os.SystemClock.elapsedRealtime();main.post(tick);restoreSession();resumeDownloads();
        main.postDelayed(new Runnable(){public void run(){if(settings.flag("cache_auto_clean",false))cleanCache(false);}},1500);
    }
    private void releaseDecoder(){invalidateNext();prepared=false;if(equalizer!=null){equalizer.release();equalizer=null;}if(player!=null){player.release();player=null;}closeStream();}
    private void createDecoder(){
        player=new MediaPlayer();player.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);player.setAudioStreamType(AudioManager.STREAM_MUSIC);bindDecoder();
    }
    private void bindDecoder(){final int epoch=generation;
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { public void onPrepared(MediaPlayer media) { if(media!=player || epoch!=generation || destroyed)return;prepared=true; applyEffects();if(savedPosition>0){seeking=true;media.seekTo(savedPosition);}else if(playIntent)startLocal();else {message="Paused";publish();} } });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer media) {
            if(media!=player || epoch!=generation || playbackCompleted)return;
            recordProgress(true);playbackCompleted=true;
            if(preparedNext!=null && playIntent && preparedNext.isPlaying()){promoteNext();return;}
            checkpoint(false);finishSession("completed");advance(1,false);
        }});
        player.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener(){public void onSeekComplete(MediaPlayer media){if(media!=player || epoch!=generation)return;seeking=false;listening.baseline(android.os.SystemClock.elapsedRealtime(),media.getCurrentPosition());checkpoint(false);restoring=false;if(playIntent && !media.isPlaying())startLocal();else {if(!playIntent)message="Paused";publish();}}});
        player.setOnInfoListener(new MediaPlayer.OnInfoListener(){public boolean onInfo(MediaPlayer media,int what,int extra){
            if(media!=player || epoch!=generation)return false;
            if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){recordProgress(false);buffering=true;}
            else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END){buffering=false;listening.baseline(android.os.SystemClock.elapsedRealtime(),media.getCurrentPosition());}return false;
        }});
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer media,int what,int extra) {
            if(media!=player || epoch!=generation)return true;
            Log.e("OnLoopio","DECODER_ERROR id="+(song==null?"":song.id)+" suffix="+(song==null?"":song.suffix)+" what="+what+" extra="+extra);
            recordProgress(false);checkpoint(false);prepared=false;
            if(!transcode && song!=null && !song.local() && online.homeWifi() && !settings.flag("force_offline",false)) {transcode=true;openStream(song.id,true);}
            else playbackFailure("Cannot decode audio");
            return true;
        }});
    }
    private void restoreSession(){final int epoch=generation;worker.submit(new Runnable(){public void run(){
        MetadataStore reader=new MetadataStore(PlaybackService.this);
        try{long until=android.os.SystemClock.elapsedRealtime()+5000;while(checkpoints.get()>0 && android.os.SystemClock.elapsedRealtime()<until && !destroyed)android.os.SystemClock.sleep(10);final SessionStore.Restored saved=new SessionStore(reader).load();main.post(new Runnable(){public void run(){if(destroyed || epoch!=generation || saved==null)return;traversal=saved.queue;syncQueue();sessionId=saved.attempt;sessionStarted=saved.started;listening.restore(saved.played,saved.qualified,saved.policy);listening.restoreClock(saved.clockWall,saved.clockElapsed,saved.clockUncertain);listening.observeClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());savedPosition=saved.position;playIntent=false;if(traversal.current()!=null)openCurrent(true);}});}
        catch(RuntimeException invalid){main.post(new Runnable(){public void run(){if(!destroyed && epoch==generation){message="Saved playback unavailable";publish();}}});}
        finally{reader.close();}
    }});}
    private void finishSession(final String outcome){
        if(sessionId==null || sessionFinished)return;sessionFinished=true;final String attempt=sessionId;
        checkpoints.incrementAndGet();stateWorker.submit(new Runnable(){public void run(){MetadataStore writer=new MetadataStore(PlaybackService.this);try{new SessionStore(writer).finish(attempt,outcome);}finally{writer.close();checkpoints.decrementAndGet();}}});
    }
    private void clearSession(){
        checkpoint(false);finishSession("skipped");sessionId=null;sessionStarted=0;playIntent=false;resumeAfterFocus=false;generation++;releaseDecoder();song=null;traversal=new PlaybackQueue(new Random());queueDirty=false;syncQueue();planNext();savedPosition=0;
        checkpoints.incrementAndGet();stateWorker.submit(new Runnable(){public void run(){MetadataStore writer=new MetadataStore(PlaybackService.this);try{new SessionStore(writer).clear();}finally{writer.close();checkpoints.decrementAndGet();}}});
        audio.abandonAudioFocus(this);publish();
    }
    private void syncQueue(){queue.clear();for(PlaybackQueue.Entry e:traversal.entries())queue.add(e.song.id);index=Math.max(0,traversal.position());playbackQueue=Collections.unmodifiableList(new ArrayList<PlaybackQueue.Entry>(traversal.entries()));}
    private void loadQueue(final List<String> ids,final String action,final int selected){
        final boolean replace=PLAY.equals(action);if(replace){pendingPlay=true;queueLoading=true;pause();generation++;releaseDecoder();message="Loading playback queue";publish();queueRequest++;}
        final int request=queueRequest;final String owner=store.accountKey()==null?"":store.accountKey();
        Set<String> reserved=new HashSet<String>(protectedPlayback);reserved.addAll(ids);protectedTracks=Collections.unmodifiableSet(reserved);
        stateWorker.submit(new Runnable(){public void run(){MetadataStore reader=new MetadataStore(PlaybackService.this);try{
            final List<PlaybackQueue.Entry> loaded=new ArrayList<PlaybackQueue.Entry>();List<Song> songs=reader.songsByIds(ids);
            for(int n=0;n<ids.size();n++){Song track=songs.get(n);if(track==null)track=new Song(ids.get(n),"Track metadata is not available.","","","",0);loaded.add(new PlaybackQueue.Entry(java.util.UUID.randomUUID().toString(),track.local()?"":owner,track));}
            main.post(new Runnable(){public void run(){if(destroyed)return;String current=store.accountKey()==null?"":store.accountKey();if(destroyed || request!=queueRequest || !owner.equals(current))return;
                try{if(replace){queueLoading=false;traversal=new PlaybackQueue(new Random());traversal.configure(settings.flag("shuffle",false),settings.number("repeat",0));traversal.replace(loaded,selected);queueDirty=true;syncQueue();playIntent=pendingPlay;pendingPlay=false;recovery.reset();if(traversal.current()!=null)openCurrent(false);}
                    else{boolean empty=traversal.current()==null;traversal.append(loaded,PLAY_NEXT.equals(action));queueDirty=true;syncQueue();if(empty && traversal.current()!=null){playIntent=false;openCurrent(false);}message=PLAY_NEXT.equals(action)?"Will play next.":"Added to playback queue.";}
                    planNext();checkpoint(true);publish();
                }catch(IllegalArgumentException full){message="Queue limit reached";publish();}
            }});
        }finally{reader.close();}}});
    }
    private void checkpointPosition(int position){boolean wasPrepared=prepared;prepared=false;savedPosition=position;checkpoint(false);prepared=wasPrepared;}
    private void checkpoint(final boolean structure){
        if(sessionId==null)return;
        listening.observeClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());
        if(prepared && player!=null)try{savedPosition=player.getCurrentPosition();}catch(IllegalStateException ignored){}
        final PlaybackQueue snapshot=new PlaybackQueue(traversal.sessionId,new Random());snapshot.restore(new ArrayList<PlaybackQueue.Entry>(traversal.entries()),new ArrayList<String>(traversal.history()),new ArrayList<String>(traversal.pending()),traversal.cursor(),traversal.shuffle(),traversal.repeat());
        snapshot.restoreForced(traversal.forcedCount());final ListeningSession progress=new ListeningSession();progress.restore(listening.playedMillis(),listening.qualified(),listening.policyVersion());progress.restoreClock(listening.clockWall(),listening.clockElapsed(),listening.clockUncertain());final String attempt=sessionId;final long started=sessionStarted;final int position=savedPosition;
        final boolean rows=queueDirty;queueDirty=false;checkpoints.incrementAndGet();stateWorker.submit(new Runnable(){public void run(){MetadataStore writer=new MetadataStore(PlaybackService.this);try{new SessionStore(writer).save(snapshot,attempt,started,progress,position,structure || rows,rows);}catch(RuntimeException failed){Log.e("OnLoopio","CHECKPOINT_FAILED reason="+failed.getClass().getSimpleName());main.post(new Runnable(){public void run(){if(!destroyed){queueDirty=true;lastSavedProgress=-1;message="Playback checkpoint failed";publish();}}});}finally{writer.close();checkpoints.decrementAndGet();}}});lastCheckpoint=android.os.SystemClock.elapsedRealtime();lastSavedProgress=listening.playedMillis();
    }
    public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null) { resumeDownloads(); return START_NOT_STICKY; }
        String action=intent.getAction();
        if(STOP.equals(action)||QUIESCE.equals(action)||NEXT.equals(action)||PREVIOUS.equals(action)||QUEUE_JUMP.equals(action)||CLEAR.equals(action)){queueRequest++;queueLoading=false;pendingPlay=false;}
        Log.i("OnLoopio","SERVICE_ACTION "+action+" startId="+startId);
        if(MEDIA_KEY.equals(action)){KeyEvent event=intent.getParcelableExtra("key");if(event!=null)mediaKey(event);return START_NOT_STICKY;}
        if(TOGGLE_LIKE.equals(action)){toggleLike();return START_NOT_STICKY;}
        if(QUIESCE.equals(action)){resumeAfterFocus=false;pendingPlay=false;pause();checkpoint(true);downloadGeneration++;stopSelf(startId);return START_NOT_STICKY;}
        if(QUEUE_JUMP.equals(action)||QUEUE_REMOVE.equals(action)||QUEUE_MOVE_NEXT.equals(action)||QUEUE_CLEAR_REMAINING.equals(action)){
            queueDirty=QUEUE_REMOVE.equals(action) || QUEUE_CLEAR_REMAINING.equals(action);
            String id=intent.getStringExtra("entry");PlaybackQueue.Entry before=traversal.current();boolean playing=playIntent;
            if(QUEUE_JUMP.equals(action)){traversal.jump(id);playIntent=true;openCurrent(false);}
            else if(QUEUE_MOVE_NEXT.equals(action))traversal.moveNext(id);
            else if(QUEUE_CLEAR_REMAINING.equals(action))traversal.clearRemaining();
            else {PlaybackQueue.Entry after=traversal.remove(id);if(before!=after){playIntent=playing;if(after==null)clearSession();else openCurrent(false);}}
            syncQueue();invalidateNext();preloadAttempt=null;planNext();checkpoint(true);publish();return START_NOT_STICKY;
        }
        if(STOP.equals(action)) { stopSelf(startId); return START_NOT_STICKY; }
        if(CLEAN.equals(action)){cleanCache(true);return START_NOT_STICKY;}
        if(REMOVE_DOWNLOAD.equals(action)){final String id=intent.getStringExtra("id");if(id!=null){store.removeDownload(id);if(downloads.song!=null && id.equals(downloads.song.id)){downloadGeneration++;busy=false;downloads=new DownloadState(null,0,-1,0,"Cancelled");}final ServerConfig config=new ConfigStore(this).load();if(config!=null)downloadWorker.submit(new Runnable(){public void run(){if(destroyed)return;try{new AudioCache(PlaybackService.this,config).discardPartial(id);}catch(IOException ignored){}}});resumeDownloads();}return START_NOT_STICKY;}
        if(CLEAR.equals(action)) { clearAudio(); return START_NOT_STICKY; }
        if(CANCEL.equals(action)) {settings.setFlag("downloads_paused",true);downloadGeneration++; busy=false;downloads=new DownloadState(null,0,-1,0,"Paused");if(song==null) message="Download paused; queued tracks remain."; publish(); return START_NOT_STICKY; }
        if(CLEAR_QUEUE.equals(action)) {downloadGeneration++;busy=false;store.clearDownloads();downloads=new DownloadState(null,0,-1,0,"Idle");if(song==null)message="Download queue cleared.";publish();return START_NOT_STICKY;}
        if(SETTINGS.equals(action)) {
            io.onloopio.device.IdleScheduler.ensure(this);downloadGeneration++;busy=false;
            if(settings.flag("force_offline",false)) {downloadGeneration++;busy=false;downloads=new DownloadState(null,0,-1,0,"Forced offline");if(proxy!=null && !queue.isEmpty()){recordProgress(false);checkpoint(false);openCurrent(true);}}
            else resumeDownloads();
            invalidateNext();preloadAttempt=null;planNext();checkpoint(true);applyEffects();if(settings.flag("cache_auto_clean",false))cleanCache(false);if(song==null && !busy && !cleaning && store.pendingDownloads()==0)stopSelf(startId);else publish();return START_NOT_STICKY;
        }
        if(RETRY.equals(action)) {settings.setFlag("downloads_paused",false);store.retryDownloads();resumeDownloads();}
        if(RESUME_PENDING.equals(action)) {settings.setFlag("downloads_paused",false);resumeDownloads();}
        if(KICK.equals(action)) {if(!settings.flag("downloads_paused",false))resumeDownloads();}
        if(PLAY.equals(action) || SYNC.equals(action) || ENQUEUE.equals(action) || APPEND.equals(action) || PLAY_NEXT.equals(action) || REMOVE.equals(action)) {
            ArrayList<String> ids=intent.getStringArrayListExtra("queue");
            if(ids==null || ids.isEmpty() || ids.size()>10000) { message="No tracks selected."; publish(); return START_NOT_STICKY; }
            if(SYNC.equals(action)||ENQUEUE.equals(action)) {settings.setFlag("downloads_paused",false);store.enqueueDownloads(ids);message="Added "+ids.size()+" tracks to download queue.";resumeDownloads();}
            else if(REMOVE.equals(action)) removeAudio(ids);
            else loadQueue(ids,action,intent.getIntExtra("index",0));


        } else if(TOGGLE.equals(action)) {
            togglePlayback();
        } else if(NEXT.equals(action)) advance(1,true);
        else if(PAUSE.equals(action)) {pendingPlay=false;resumeAfterFocus=false; pause();}
        else if(RESUME.equals(action)) {resumeAfterFocus=false;if(queueLoading)pendingPlay=true;else{playIntent=true;if(prepared)startLocal();else if(traversal.current()!=null)openCurrent(true);}}
        else if(PREVIOUS.equals(action)) { if(prepared && player.getCurrentPosition()>3000) seekTo(0); else advance(-1,true); }
        else if(SEEK.equals(action) && prepared) seekTo(Math.max(0,Math.min(player.getDuration(),player.getCurrentPosition()+intent.getIntExtra("seconds",0)*1000)));
        planNext();publish(); return START_NOT_STICKY;
    }
    private void closeStream() { if(proxy!=null) { proxy.close(); proxy=null; } }
    private void newSession(){sessionFinished=false;healthyMillis=0;listening.reset();sessionId=java.util.UUID.randomUUID().toString();sessionStarted=0;seeking=false;buffering=false;playbackCompleted=false;cancelPlayGesture();}
    private void recordProgress(boolean completing){
        if(!prepared || song==null || sessionStarted==0)return;
        int position,duration;boolean advancing;
        try{position=player.getCurrentPosition();duration=player.getDuration();advancing=completing || player.isPlaying();}
        catch(IllegalStateException decoderUnavailable){return;}
        listening.observeClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());long prior=listening.playedMillis();boolean qualified=listening.sample(android.os.SystemClock.elapsedRealtime(),position,duration,advancing && !seeking && !buffering);healthyMillis+=Math.max(0,listening.playedMillis()-prior);if(healthyMillis>=1000)recovery.reset();
        if(qualified){
            final ListenEvent event=new ListenEvent(sessionId,playbackAccount,song.id,sessionStarted);final long qualifiedAt=System.currentTimeMillis();final boolean local=song.local(),uncertain=listening.clockUncertain();checkpoints.incrementAndGet();stateWorker.submit(new Runnable(){public void run(){MetadataStore writer=new MetadataStore(PlaybackService.this);try{writer.recordListening(event,qualifiedAt,uncertain);if(!local)PlaylistSyncService.requestFeedback(PlaybackService.this);}catch(RuntimeException failed){Log.e("OnLoopio","QUALIFICATION_FAILED reason="+failed.getClass().getSimpleName());main.post(new Runnable(){public void run(){if(!destroyed && event.sessionId.equals(sessionId))listening.restore(listening.playedMillis(),false,listening.policyVersion());}});}finally{writer.close();checkpoints.decrementAndGet();}}});
        }
    }
    private void seekTo(int position){invalidateNext();preloadAttempt=null;recordProgress(false);savedPosition=position;checkpointPosition(position);seeking=true;player.seekTo(position);}
    private String playContext(){String active=store.accountKey();return (active==null?"":active)+":"+generation+":"+(song==null?"":song.id);}
    private void releaseGestureWake(){if(gestureWake!=null && gestureWake.isHeld())gestureWake.release();}
    private void cancelPlayGesture(){playGesture.cancel();main.removeCallbacks(finishPlayGesture);releaseGestureWake();}
    private final Runnable finishPlayGesture=new Runnable(){public void run(){
        applyPlayGesture(playGesture.finish(android.os.SystemClock.uptimeMillis(),playContext(),ControlLock.locked(PlaybackService.this)));
        releaseGestureWake();
    }};
    private void mediaKey(KeyEvent event){
        if(event.getKeyCode()!=KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (event.getAction()!=KeyEvent.ACTION_DOWN && event.getAction()!=KeyEvent.ACTION_UP))return;
        if(gestureWake==null){gestureWake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OnLoopio:play-gesture");gestureWake.setReferenceCounted(false);}
        gestureWake.acquire(PlayGesture.GAP_MS+1000);
        int result=playGesture.key(event.getAction()==KeyEvent.ACTION_DOWN,event.isCanceled(),event.getRepeatCount(),event.getDownTime(),event.getEventTime(),playContext(),ControlLock.locked(this));
        main.removeCallbacks(finishPlayGesture);applyPlayGesture(result);
        long remaining=playGesture.remaining(android.os.SystemClock.uptimeMillis());if(remaining>=0)main.postDelayed(finishPlayGesture,remaining);else if(event.getAction()==KeyEvent.ACTION_UP)releaseGestureWake();
    }
    private void applyPlayGesture(int result){if(result==PlayGesture.SINGLE)togglePlayback();else if(result==PlayGesture.DOUBLE)toggleLike();}
    private void togglePlayback(){resumeAfterFocus=false;if(queueLoading){pendingPlay=!pendingPlay;if(!pendingPlay)pause();return;}if(prepared){if(player.isPlaying())pause();else {playIntent=true;startLocal();}}else if(!queue.isEmpty()){playIntent=!playIntent;if(playIntent)openCurrent(true);else pause();}}
    private void toggleLike(){
        if(song==null || ControlLock.locked(this))return;
        try{boolean liked=store.toggleLike(playbackAccount,song.id);new DeviceSettings(this).feedback();publish();
            android.widget.Toast.makeText(this,liked?io.onloopio.ui.Ui.label(this,"Liked"):io.onloopio.ui.Ui.label(this,"Like removed"),android.widget.Toast.LENGTH_SHORT).show();
            PlaylistSyncService.feedbackChanged(this);
        }catch(IllegalStateException changed){cancelPlayGesture();}
    }
    private void open(int position){if(position<0 || position>=traversal.entries().size())return;traversal.jump(traversal.entries().get(position).id);openCurrent(false);}
    private void openCurrent(boolean preserve) {
        recordProgress(false);if(!preserve){finishSession(playbackCompleted?"completed":"skipped");newSession();savedPosition=0;}
        generation++;healthyMillis=0;preloadAttempt=null;releaseDecoder();createDecoder();syncQueue();PlaybackQueue.Entry current=traversal.current();song=current==null?null:current.song;playbackAccount=current==null?"":current.account;transcode=false;sourceNormalized=false;gainFile=null;gainToken=null;restoring=preserve && savedPosition>0;
        checkpoint(true);
        if(song==null){message="Track metadata is not available.";publish();return;}
        if(!android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState())){playIntent=false;message="Storage unavailable";publish();return;}
        planNext();if(song.local()){File file=new File(song.localPath);try{String base=io.onloopio.library.MusicPaths.root().getCanonicalPath()+File.separator;if(!file.getCanonicalPath().startsWith(base) || !file.isFile())throw new IOException("Missing local file");gainFile=file;prepare(file.getPath(),true);}catch(Exception missing){playbackFailure("Local file unavailable");}return;}
        ServerConfig config=new ConfigStore(this).load();
        try {
            File cached=AudioCache.savedFile(this,playbackAccount,song);if(cached!=null){gainFile=cached;gainToken=AudioCache.tokenAt(this,playbackAccount,cached);sourceNormalized=AudioCache.normalizedSource(this,playbackAccount,song,cached);prepare(cached.getAbsolutePath(),true);return;}
            if(!playIntent){message="Paused";publish();return;}
            if(config==null || !config.accountKey().equals(playbackAccount)){playIntent=false;message="Account changed";publish();return;}
            if(settings.flag("force_offline",false) || !online.homeWifi()){playbackFailure("Track is not downloaded.");return;}
            final int request=generation;final String id=song.id;final ServerConfig server=config;
            message="Connecting to Navidrome…";publish();worker.submit(new Runnable(){public void run(){final boolean ok=online.check(server);main.post(new Runnable(){public void run(){
                if(request!=generation || !playIntent)return;if(ok){transcode=!("mp3".equalsIgnoreCase(song.suffix)||"flac".equalsIgnoreCase(song.suffix));openStream(id,transcode);}else {pause();message="Offline: Navidrome is unavailable.";publish();}
            }});}});
        }catch(IOException failure){pause();message="Storage unavailable";publish();}
    }
    private void openStream(String id,boolean fallback) {
        ServerConfig config=new ConfigStore(this).load(); if(config==null) return;
        try {
            generation++;releaseDecoder();createDecoder();proxy=new StreamProxy(config);restoring=savedPosition>0;
            gainFile=null;gainToken=null;sourceNormalized=fallback;proxy.setTranscode(fallback); prepare(proxy.url(id),false);
        } catch(IOException failure) {message="Cannot start audio stream.";publish();}
    }
    private void prepare(String source,boolean local) {
        try {
            message=local?"Opening offline audio…":"Buffering stream…"; publish();
            player.setDataSource(source); player.prepareAsync();
            remote.editMetadata(true).putString(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE,song.title)
                .putString(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST,song.artist)
                .putString(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM,song.album).apply();
        } catch(Exception failure) { Log.e("OnLoopio","PREPARE_FAILED suffix="+song.suffix,failure); playbackFailure("Cannot open audio."); }
    }
    private void preloadNext(){
        if(!settings.flag("gapless_experimental",false) || !prepared || !playIntent || proxy!=null || song==null || !song.suffix.matches("(?i)flac|wav") || traversal.repeat()!=0 || settings.number("eq_preset",-1)!=-1)return;
        final PlaybackQueue.Entry upcoming=traversal.peek(true);if(upcoming==null || upcoming.id.equals(preparedEntry) || upcoming.id.equals(preloadAttempt) || !upcoming.song.suffix.matches("(?i)flac|wav"))return;invalidateNext();preloadAttempt=upcoming.id;
        try{File file=upcoming.song.local()?new File(upcoming.song.localPath):AudioCache.savedFile(this,upcoming.account,upcoming.song);if(file==null || !file.isFile())return;if(upcoming.song.local() && !file.getCanonicalPath().startsWith(io.onloopio.library.MusicPaths.root().getCanonicalPath()+File.separator))return;
            // Requested/original suffix can differ from Compact's actual MP3 artifact.
            if(gainFile==null || !AudioCache.audioExtension(gainFile,"").matches("flac|wav") || !AudioCache.audioExtension(file,"").matches("flac|wav"))return;
            final MediaPlayer next=new MediaPlayer();preparedNext=next;preparedEntry=upcoming.id;final int epoch=generation;next.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);next.setAudioStreamType(AudioManager.STREAM_MUSIC);next.setDataSource(file.getPath());
            preparedGainFile=file;preparedGainToken=upcoming.song.local()?null:AudioCache.tokenAt(this,upcoming.account,file);ReplayGain gain=gainFor(upcoming.account,upcoming.song,file,preparedGainToken);preparedNormalized=!upcoming.song.local() && AudioCache.normalizedSource(this,upcoming.account,upcoming.song,file);float volume=gain.volume(settings.number("replay_gain",0),settings.number("replay_gain_preamp",0),0,preparedNormalized);next.setVolume(volume,volume);
            next.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer ready){if(ready!=preparedNext || epoch!=generation || !playIntent){if(ready==preparedNext)invalidateNext();return;}try{player.setNextMediaPlayer(ready);}catch(RuntimeException unsupported){invalidateNext();}}});
            next.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer media,int what,int extra){if(media==preparedNext)invalidateNext();return true;}});next.prepareAsync();
        }catch(Exception unavailable){invalidateNext();}
    }
    private void promoteNext(){
        recordProgress(true);checkpoint(false);finishSession("completed");MediaPlayer old=player;player=preparedNext;preparedNext=null;preparedEntry=null;old.setOnCompletionListener(null);old.release();preloadAttempt=null;
        traversal.next(true);syncQueue();song=traversal.current().song;playbackAccount=traversal.current().account;newSession();savedPosition=0;sourceNormalized=preparedNormalized;gainFile=preparedGainFile;gainToken=preparedGainToken;prepared=true;playIntent=true;sessionStarted=System.currentTimeMillis();listening.observeClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());listening.baseline(android.os.SystemClock.elapsedRealtime(),player.getCurrentPosition());checkpoint(true);applyEffects();
        bindDecoder();
        planNext();publish();preloadNext();
    }
    private void startLocal() {
        if(!prepared || !playIntent || seeking || destroyed || !android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState()))return;
        if(audio.requestAudioFocus(this,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {message="Audio output is in use.";publish();return;}
        try {if(playbackCompleted){newSession();seekTo(0);}recordProgress(false);player.start();preloadAttempt=null;if(sessionStarted==0)sessionStarted=System.currentTimeMillis();listening.observeClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());listening.baseline(android.os.SystemClock.elapsedRealtime(),player.getCurrentPosition());message=proxy==null?"Playing offline":"Streaming from Navidrome"; remote.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING); publish();}
        catch(RuntimeException failure) {message="Audio output failed.";publish();}
    }
    private void pause(){playIntent=false;invalidateNext();recordProgress(false);if(prepared && player.isPlaying())player.pause();checkpoint(false);message="Paused";if(remote!=null)remote.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);publish();}
    private void advance(int direction,boolean user){
        if(queue.isEmpty())return;recordProgress(false);checkpoint(false);if(user)recovery.reset();
        traversal.configure(settings.flag("shuffle",false),settings.number("repeat",0));PlaybackQueue.Entry next=direction>0?traversal.next(!user):traversal.previous();
        if(next==null){pause();message="End of queue";remote.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);audio.abandonAudioFocus(this);publish();return;}
        playIntent=user || playIntent;openCurrent(false);
    }
    private void playbackFailure(String reason){
        PlaybackQueue.Entry failed=traversal.current();recordProgress(false);checkpoint(false);finishSession("error");
        if(failed==null || !playIntent || settings.flag("stop_on_error",false) || !recovery.fail(failed.id)){pause();message=reason;publish();return;}
        PlaybackQueue.Entry next=traversal.next(false);
        if(next==null || recovery.tried(next.id)){pause();message="No playable tracks";publish();return;}
        final int epoch=generation;message=reason;publish();main.post(new Runnable(){public void run(){if(epoch==generation && playIntent)openCurrent(false);}});
    }
    private void planNext(){
        traversal.configure(settings.flag("shuffle",false),settings.number("repeat",0));PlaybackQueue.Entry upcoming=traversal.peek(true);if(preparedEntry!=null && (upcoming==null || !preparedEntry.equals(upcoming.id)))invalidateNext();nextSong=upcoming==null?null:upcoming.song;nextIndex=upcoming==null?-1:traversal.entries().indexOf(upcoming);
        Set<String> keep=new HashSet<String>();for(PlaybackQueue.Entry e:traversal.entries())keep.add(e.song.id);protectedPlayback=Collections.unmodifiableSet(keep);protectedTracks=protectedPlayback;
    }
    private void cleanCache(final boolean manual){if(cleaning)return;final ServerConfig config=new ConfigStore(this).load();if(config==null)return;cleaning=true;lastCleanup=android.os.SystemClock.elapsedRealtime();
        downloadWorker.submit(new Runnable(){public void run(){if(destroyed)return;MetadataStore store=new MetadataStore(PlaybackService.this);try{CachePolicy.Result r=new CachePolicy(PlaybackService.this,store,new AudioCache(PlaybackService.this,config)).clean(protectedPlayback,0,System.currentTimeMillis(),manual);cleanupMessage="Removed "+r.removed+" tracks · "+r.freed/(1024*1024)+" MiB"+(r.blocked?" · protected tracks exceed limit":"");}catch(Exception failed){cleanupMessage="Cannot clean cache";}finally{store.close();main.post(new Runnable(){public void run(){cleaning=false;}});}}});
    }
    private void resumeDownloads() {
        if(io.onloopio.sync.WorkGate.reason(this,io.onloopio.sync.WorkPolicy.Work.DOWNLOAD,false).length()>0)return;
        if(!android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState()))return;
        if(busy || settings.flag("downloads_paused",false) || store.pendingDownloads()==0) return;
        final ServerConfig config=new ConfigStore(this).load();
        if(config==null || settings.flag("force_offline",false) || !online.homeWifi()) return;
        busy=true; final int request=++downloadGeneration;downloads=new DownloadState(null,0,-1,0,"Connecting"); publish();
        downloadWorker.submit(new Runnable(){public void run(){
            if(destroyed || request!=downloadGeneration)return;final MetadataStore store=new MetadataStore(PlaybackService.this);
            PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OnLoopio:download"); wake.acquire(3600000);
            int completed=0,failed=0;boolean attempted=false,successful=false;final long deadline=android.os.SystemClock.elapsedRealtime()+300000;android.net.wifi.WifiManager.WifiLock wifi=null;
            try {
                attempted=true;if(!online.check(config)) return;wifi=((android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL,"OnLoopio:download");wifi.setReferenceCounted(false);wifi.acquire();
                final AudioCache cache=new AudioCache(PlaybackService.this,config); NavidromeClient client=new NavidromeClient(config);
                final CachePolicy policy=new CachePolicy(PlaybackService.this,store,cache);
                String id; while(request==downloadGeneration && completed+failed<20 && android.os.SystemClock.elapsedRealtime()<deadline && (id=store.nextDownload())!=null) {
                    if(!online.homeWifi() || settings.flag("force_offline",false)) break;
                    try {
                        final int active=request;final String trackId=id;final Song entry=store.song(id);if(entry==null){store.completedDownload(id);continue;}
                        downloads=new DownloadState(entry,0,-1,0,"Downloading");store.downloadProgress(id,0,-1);
                        final long started=android.os.SystemClock.elapsedRealtime();
                        File saved=cache.obtain(entry,client,new NavidromeClient.DownloadProgress(){long lastPersist,lastSpaceCheck;public void bytes(long count,long total)throws IOException{
                            if(destroyed || android.os.SystemClock.elapsedRealtime()>=deadline || active!=downloadGeneration || !config.accountKey().equals(store.accountKey()) || cache.profile()!=store.offlineProfile() || io.onloopio.sync.WorkGate.reason(PlaybackService.this,io.onloopio.sync.WorkPolicy.Work.DOWNLOAD,false).length()>0)throw new IOException("Cancelled");
                            if(lastSpaceCheck==0 || count-lastSpaceCheck>=1024*1024){lastSpaceCheck=count;policy.requireSpace(protectedPlayback,total>0?total:count+1024*1024,count);}
                            long now=android.os.SystemClock.elapsedRealtime();downloads=new DownloadState(entry,count,total,count*1000/Math.max(1,now-started),"Downloading");
                            if(now-lastPersist>1000){store.downloadProgress(trackId,count,total);lastPersist=now;}
                        }});
                        if(request!=downloadGeneration || !config.accountKey().equals(store.accountKey()) || cache.profile()!=store.offlineProfile() || io.onloopio.sync.WorkGate.reason(PlaybackService.this,io.onloopio.sync.WorkPolicy.Work.DOWNLOAD,false).length()>0)break;
                        store.downloadProgress(id,saved.length(),saved.length());store.downloaded(id,System.currentTimeMillis());store.completedDownload(id);completed++;io.onloopio.device.IdleScheduler.activity(PlaybackService.this);
                        new io.onloopio.db.GenerationStore(store).reconcile(config.accountKey(),cache.completedNames(),System.currentTimeMillis());
                        new io.onloopio.db.ArtifactStore(store).record(config.accountKey(),entry,cache.profile(),saved);
                        try{ReplayGain gain=client.replayGain(entry.id);io.onloopio.db.GainStore gains=new io.onloopio.db.GainStore(store);gains.save(config.accountKey(),entry.id,gain);gains.saveArtifact(config.accountKey(),AudioProfile.token(entry.id,cache.profile()),saved,gain,AudioCache.normalizedSource(PlaybackService.this,config.accountKey(),entry,saved));}catch(IOException optionalGain){}
                        if(destroyed || request!=downloadGeneration || !config.accountKey().equals(store.accountKey()) || io.onloopio.sync.WorkGate.reason(PlaybackService.this,io.onloopio.sync.WorkPolicy.Work.DOWNLOAD,false).length()>0)break;
                        downloads=new DownloadState(null,0,-1,0,"Loading cover");
                        try{android.graphics.Bitmap cover=new CoverCache(PlaybackService.this,config).load(entry,client,true);if(cover!=null)cover.recycle();}catch(Exception optionalCover){ }
                    } catch(Exception error) {
                        if(destroyed || android.os.SystemClock.elapsedRealtime()>=deadline || request!=downloadGeneration || io.onloopio.sync.WorkGate.reason(PlaybackService.this,io.onloopio.sync.WorkPolicy.Work.DOWNLOAD,false).length()>0)break;
                        Log.w("OnLoopio","DOWNLOAD_FAILED id="+id+" reason="+error.getClass().getSimpleName());
                        store.failedDownload(id,error.getMessage()!=null && error.getMessage().startsWith("Cache limit")?"Cache limit / protected tracks / SD reserve":"Network, server or audio error");failed++;
                    }
                }
                successful=failed==0;
            } catch(Exception error) {Log.w("OnLoopio","Download queue interrupted: "+error.getClass().getSimpleName());}
            finally {if(wifi!=null && wifi.isHeld())wifi.release();if(wake.isHeld()) wake.release();if(attempted && !destroyed && request==downloadGeneration && io.onloopio.sync.WorkGate.reason(PlaybackService.this,io.onloopio.sync.WorkPolicy.Work.DOWNLOAD,true).length()==0)io.onloopio.sync.WorkGate.result(PlaybackService.this,config.accountKey(),io.onloopio.sync.WorkPolicy.Work.DOWNLOAD,successful,false);store.close(); final int done=completed,errors=failed; main.post(new Runnable(){public void run(){ if(request==downloadGeneration) {busy=false;downloads=new DownloadState(null,0,-1,0,settings.flag("force_offline",false)||!online.homeWifi()?"Waiting for home Wi-Fi":errors>0?"Some downloads failed":PlaybackService.this.store.pendingDownloads()>0?"Waiting for Navidrome":"Idle");if(song==null)message="Downloads: "+done+" saved, "+errors+" failed, "+PlaybackService.this.store.pendingDownloads()+" queued";publish();if(errors==0 && PlaybackService.this.store.pendingDownloads()>0)main.postDelayed(new Runnable(){public void run(){resumeDownloads();}},1500);}}});}
        }});
    }
    private void removeAudio(final List<String> ids) {
        final String playing=song==null?null:song.id; final ServerConfig config=new ConfigStore(this).load(); if(config==null)return;
        if(playing!=null && ids.contains(playing)) {recordProgress(false);clearSession();}
        downloadGeneration++; busy=false;
        downloadWorker.submit(new Runnable(){public void run(){
            if(destroyed)return;MetadataStore store=new MetadataStore(PlaybackService.this);try {AudioCache cache=new AudioCache(PlaybackService.this,config); int removed=0;
                for(String id:ids) {if(id.startsWith("local:"))continue;store.removeDownload(id);for(AudioCache.Artifact artifact:cache.artifacts(id))if(cache.removeArtifact(artifact.token))removed++;cache.discardPartial(id);}
                new io.onloopio.db.GenerationStore(store).reconcile(config.accountKey(),cache.completedNames(),System.currentTimeMillis());
                sendBroadcast(new Intent("io.onloopio.PLAYLISTS_UPDATED").setPackage(getPackageName()));
                final int count=removed; main.post(new Runnable(){public void run(){message="Removed "+count+" offline tracks.";publish();}});
            } catch(IOException error) {main.post(new Runnable(){public void run(){message="Cannot remove offline audio.";publish();}});}finally{store.close();}
        }});
    }
    private void clearAudio() {
        recordProgress(false);cancelPlayGesture();downloadGeneration++; busy=false; store.stopFollowingPlaylists();store.clearDownloads();clearSession();
        final ServerConfig config=new ConfigStore(this).load(); if(config==null)return;
        downloadWorker.submit(new Runnable(){public void run(){MetadataStore metadata=new MetadataStore(PlaybackService.this);try {AudioCache cache=new AudioCache(PlaybackService.this,config);cache.clear();new io.onloopio.db.GenerationStore(metadata).reconcile(config.accountKey(),cache.completedNames(),System.currentTimeMillis());sendBroadcast(new Intent("io.onloopio.PLAYLISTS_UPDATED").setPackage(getPackageName()));main.post(new Runnable(){public void run(){message="Downloaded audio cleared.";publish();}});}catch(Exception failure){main.post(new Runnable(){public void run(){message="Cannot clear SD audio.";publish();}});}finally{metadata.close();}}});
    }
    private void applyEffects() {
        if(equalizer!=null) {equalizer.release();equalizer=null;} int preset=settings.number("eq_preset",-1); if(!prepared || preset==-1){applyGain();return;}
        try {equalizer=new Equalizer(0,player.getAudioSessionId());
            if(preset==-2) {short[] range=equalizer.getBandLevelRange();for(short n=0;n<equalizer.getNumberOfBands();n++) equalizer.setBandLevel(n,(short)Math.max(range[0],Math.min(range[1],settings.number("eq_band_"+n,0)*100)));}
            else if(preset>=0 && preset<equalizer.getNumberOfPresets()) equalizer.usePreset((short)preset); equalizer.setEnabled(true);
        } catch(RuntimeException failure) {if(equalizer!=null)equalizer.release();equalizer=null;}
        applyGain();
    }
    private ReplayGain gainFor(String owner,Song entry,File file,String token){io.onloopio.db.GainStore gains=new io.onloopio.db.GainStore(store);return entry.local() && file!=null?gains.local(entry.id,file):token!=null && file!=null?gains.artifact(owner,token,file):file==null?gains.load(owner,entry.id):new ReplayGain(null,null,null,null,null,null);}
    private void applyGain(){if(!prepared || song==null)return;double headroom=0;if(equalizer!=null)try{for(short band=0;band<equalizer.getNumberOfBands();band++)headroom+=Math.max(0,equalizer.getBandLevel(band)/100.0);}catch(RuntimeException unavailable){headroom=15;}gainVolume=gainFor(playbackAccount,song,gainFile,gainToken).volume(settings.number("replay_gain",0),settings.number("replay_gain_preamp",0),headroom,sourceNormalized);player.setVolume(gainVolume*(ducked?.2f:1),gainVolume*(ducked?.2f:1));}
    private void publish() {
        if(destroyed)return;
        boolean playing=prepared && player.isPlaying(); int position=prepared?player.getCurrentPosition():savedPosition, duration=prepared?player.getDuration():song==null?0:song.duration*1000;
        state=new State(song,message,playing,busy,position,duration,nextSong,queue.isEmpty()?0:index+1,queue.size(),song!=null && store.isLiked(song.id));
        PendingIntent home=PendingIntent.getActivity(this,0,new Intent(this,PlaylistActivity.class).setAction("io.onloopio.OPEN_PLAYER"),PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this).setSmallIcon(R.drawable.icon).setContentTitle(song==null?"OnLoopio":song.title).setContentText(message).setContentIntent(home).setOngoing(playing||busy).build();
        if(song!=null || busy) startForeground(7,notification); else stopForeground(true);
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(prepared && player.isPlaying())io.onloopio.device.IdleScheduler.activity(PlaybackService.this);
        if(settings.flag("idle_shutdown",false)){Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));boolean powered=battery!=null && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0)!=0;if(io.onloopio.sync.IdlePolicy.shutdown(true,prepared && player.isPlaying(),busy || PlaylistSyncService.busy || io.onloopio.library.MusicLibraryService.running,settings.flag("usb_quiescing",false),powered,android.os.SystemClock.elapsedRealtime(),DeviceSettings.lastActivity)){io.onloopio.device.PowerReceiver.powerOff(PlaybackService.this);return;}}
        if(settings.flag("cache_auto_clean",false) && android.os.SystemClock.elapsedRealtime()-lastCleanup>21600000)cleanCache(false);
        if(ControlLock.locked(PlaybackService.this))cancelPlayGesture();
        if(prepared && listening.playedMillis()>lastSavedProgress && android.os.SystemClock.elapsedRealtime()-lastCheckpoint>=10000)checkpoint(false);
        if(prepared && playIntent && preparedNext==null)preloadNext();
        if(proxy!=null && (settings.flag("force_offline",false) || !online.homeWifi())) {recordProgress(false);checkpoint(false);try{File cached=AudioCache.savedFile(PlaybackService.this,playbackAccount,song);if(cached!=null)openCurrent(true);else {pause();releaseDecoder();message="Offline: stream stopped.";publish();}}catch(IOException unavailable){pause();releaseDecoder();}}
        if(prepared){recordProgress(false);int position=player.getCurrentPosition(),duration=player.getDuration();state=new State(song,message,player.isPlaying(),busy,position,duration,nextSong,queue.isEmpty()?0:index+1,queue.size(),song!=null && store.isLiked(song.id));}main.postDelayed(this,500);
    }};
    public void onAudioFocusChange(int change) {
        if(change==AudioManager.AUDIOFOCUS_LOSS){resumeAfterFocus=false;pause();}
        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){resumeAfterFocus=prepared && player.isPlaying();pause();}
        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){ducked=true;invalidateNext();applyGain();}
        else if(change==AudioManager.AUDIOFOCUS_GAIN){ducked=false;applyGain();if(resumeAfterFocus){resumeAfterFocus=false;playIntent=true;startLocal();}}
    }
    public void onDestroy(){recordProgress(false);checkpoint(false);cancelPlayGesture();destroyed=true;downloadGeneration++;generation++;main.removeCallbacksAndMessages(null);worker.shutdownNow();downloadWorker.shutdownNow();releaseDecoder();audio.abandonAudioFocus(this);audio.unregisterMediaButtonEventReceiver(buttons);audio.unregisterRemoteControlClient(remote);unregisterReceiver(noisy);unregisterReceiver(feedbackUpdated);unregisterReceiver(lifecycle);store.close();stopForeground(true);state=new State(null,"Choose a track to play.",false,false,0,0);downloads=new DownloadState(null,0,-1,0,"Idle");protectedTracks=Collections.emptySet();super.onDestroy();}
    public IBinder onBind(Intent intent){return null;}
}
