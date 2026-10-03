package io.onloopio.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import io.onloopio.config.ConfigStore;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.Connectivity;
import io.onloopio.device.OnlineMode;
import io.onloopio.device.DeviceSettings;
import io.onloopio.device.Y1Keys;
import io.onloopio.device.ControlLock;
import io.onloopio.device.CenterGesture;
import io.onloopio.device.WheelTurnGuard;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Song;
import io.onloopio.model.Library;
import io.onloopio.player.AudioCache;
import io.onloopio.player.CoverCache;
import android.graphics.Bitmap;
import io.onloopio.player.MediaButtons;
import io.onloopio.player.PlaybackService;
import io.onloopio.sync.PlaylistSyncService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Sole HOME: music menu, synchronized library and controls for completed local audio. */
public final class PlaylistActivity extends Activity {
    public static volatile PlaylistActivity foreground;
    private static final int HOME=0, PLAYLISTS=1, DETAIL=2, ARTISTS=3, ALBUMS=4, TRACKS=5, PLAYER=6, CONTEXT=7, GENRES=8, PLAYER_MENU=9, POWER_CONFIRM=10,FAVORITES=11;
    private final Handler main=new Handler(); private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<String> labels=new ArrayList<String>(); private final RowAdapter adapter=new RowAdapter();
    private ConfigStore configs; private MetadataStore store; private ServerConfig config; private DeviceSettings settings; private OnlineMode online;
    private TextView title,status; private ListView list; private int screen=PLAYER; private volatile int generation;
    private static final class ListPosition {
        final String row;final int index,top;
        ListPosition(String row,int index,int top){this.row=row;this.index=index;this.top=top;}
    }
    private final java.util.Map<String,ListPosition> positions=new java.util.LinkedHashMap<String,ListPosition>(16,.75f,true) {
        protected boolean removeEldestEntry(java.util.Map.Entry<String,ListPosition> entry){return size()>64;}
    };
    private String renderedList;private final List<String> renderedRows=new ArrayList<String>();
    private List<Playlist> playlists=new ArrayList<Playlist>(); private List<Song> tracks=new ArrayList<Song>();
    private final List<String> groups=new ArrayList<String>(); private Playlist current; private PlaylistDetail detail;
    private final java.util.Map<String,String> albumNames=new java.util.HashMap<String,String>();
    private final java.util.Map<String,String> artistNames=new java.util.HashMap<String,String>();
    private String accountKey,artist,artistKey,album,genre; private Future<?> pending; private boolean firstResume=true; private AudioCache audioCache; private long lastRefreshAttempt;
    private final java.util.Map<String,String> albumKeyByName=new java.util.HashMap<String,String>();
    private final java.util.Map<String,String> artistNameByKey=new java.util.HashMap<String,String>(),artistKeyByName=new java.util.HashMap<String,String>();
    private final BroadcastReceiver localUpdated=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(screen!=CONTEXT)render(true);}};
    private List<Song> contextTracks=new ArrayList<Song>();private String contextTitle,contextPlaylistId;private int previousScreen;
    private String contextSongId,contextAccount,menuSongId,menuAccount;private int contextLikeIndex=-1,playerLikeIndex=-1;private java.util.Set<String> likedIds=Collections.emptySet();
    private final BroadcastReceiver feedbackUpdated=new BroadcastReceiver(){public void onReceive(Context c,Intent i){render(true);}};
    private final BroadcastReceiver synchronizedPlaylists=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(!i.getBooleanExtra("reachable",i.getBooleanExtra("success",false)))online.failed();if(current!=null){detail=store.detail(current.id);if(detail==null){current=null;if(screen==DETAIL)screen=PLAYLISTS;}else current=detail.playlist;}if(screen!=CONTEXT)render(true);}};
    private NowPlayingView playerView;private final ExecutorService coversWorker=Executors.newSingleThreadExecutor();private String artworkKey;private int artworkRequest;private Bitmap currentCover,nextCover;
    private Runnable pendingTap; private int lastTapPosition=-1; private long lastTapAt;
    private CenterGesture center;private WheelTurnGuard wheelGuard;private boolean wheelSeeking;private int centerScreen,centerPosition;private String centerRow;
    public void onCreate(Bundle state) {
        super.onCreate(state); configs=new ConfigStore(this); store=new MetadataStore(this); settings=new DeviceSettings(this); online=new OnlineMode(this);
        wheelGuard=new WheelTurnGuard(settings.number("wheel_steps_per_turn",WheelTurnGuard.DEFAULT_STEPS_PER_TURN));
        center=new CenterGesture(this,main,new CenterGesture.Listener(){
            public void started(){cancelTap();wheelGuard.reset();centerScreen=screen;centerPosition=Math.max(0,list.getSelectedItemPosition());centerRow=selectionKey(centerPosition);}
            public void shortPress(int count){
                if(!sameCenterSelection())return;settings.feedback();
                if(screen==PLAYER){if(count==1){wheelSeeking=!wheelSeeking;wheelGuard.reset();playerStatus();}}
                else if(count==2)playEntity(centerPosition);else activate(centerPosition);
            }
            public void longPress(){
                if(!sameCenterSelection())return;settings.feedback();
                if(screen==PLAYER)playerMenu();else if(entityPosition(centerPosition))openContext(centerPosition);
            }
            public void lockChanged(boolean locked){settings.feedback();cancelTap();wheelGuard.reset();screen=PLAYER;render(false);}
        });
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        createPage();
    }
    private void createPage() {
        renderedList=null;
        LinearLayout root=Ui.page(this); title=Ui.title(this,"OnLoopio"); root.addView(title);
        status=Ui.text(this,"",12,Ui.FG); root.addView(status);
        list=new ListView(this); list.setCacheColorHint(Ui.BG); list.setDividerHeight(1); list.setSoundEffectsEnabled(false);
        list.setAdapter(adapter); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(Ui.text(this,"Wheel: browse · Center: open · Double: play · Hold: options",10,Ui.ACCENT)); setContentView(root);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent,View view,int position,long id) { if(ControlLock.locked(PlaylistActivity.this))return;settings.feedback();tapped(position); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {public boolean onItemLongClick(AdapterView<?> p,View v,int pos,long id){if(!ControlLock.locked(PlaylistActivity.this))openContext(pos);return true;}});
    }
    protected void onResume() {super.onResume();foreground=this;registerReceiver(synchronizedPlaylists,new IntentFilter(PlaylistSyncService.UPDATED));registerReceiver(feedbackUpdated,new IntentFilter(PlaylistSyncService.FEEDBACK_UPDATED));registerReceiver(localUpdated,new IntentFilter(io.onloopio.library.MusicLibraryService.UPDATED));io.onloopio.library.MusicLibraryService.request(this,false);importControls();rememberSelection();if(ControlLock.locked(this))screen=PLAYER;createPage();loadConfiguration(false);if(current!=null){detail=store.detail(current.id);if(detail==null){current=null;if(screen==DETAIL)screen=PLAYLISTS;}else current=detail.playlist;}if(config!=null && online.homeWifi() && !settings.flag("force_offline",false) && !online.online() && pending==null)checkMode();render(true);main.post(tick);}
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        center.cancel();wheelGuard.reset();cancelTap();
        importControls();
        if("io.onloopio.OPEN_LIBRARY".equals(intent.getAction())) screen=HOME;
        else if("io.onloopio.OPEN_PLAYER".equals(intent.getAction())) screen=PLAYER;
        else if(Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME)) screen=PLAYER;
        if(ControlLock.locked(this))screen=PLAYER;
        loadConfiguration("io.onloopio.action.RELOAD".equals(intent.getAction()));
    }
    private void loadConfiguration(boolean reload) {
        boolean imported=configs.importPrivateFile(); if(io.onloopio.config.UsbSetup.importFromUsb(this))imported=true; ServerConfig loaded=configs.load();
        boolean changed=loaded!=null && !loaded.accountKey().equals(accountKey);
        boolean accountSwitched=changed && accountKey!=null;
        config=loaded;
        if(changed) {
            invalidate(); if(accountKey!=null) PlaybackService.action(this,PlaybackService.STOP);
            positions.clear();renderedList=null;
            accountKey=config.accountKey(); store.selectAccount(accountKey); current=null; detail=null; artist=null;artistKey=null;album=null;genre=null;screen=PLAYER;artworkKey=null;
            io.onloopio.library.MusicLibraryService.request(this,true);
        }
        try {if(config==null)audioCache=null;else if(audioCache==null || changed)audioCache=new AudioCache(this,config);else audioCache.refreshIfStale();}catch(Exception unavailable){audioCache=null;}
        if(store.needsAudioIndex())worker.submit(new Runnable(){public void run(){try{store.backfillAudioIndex();main.post(new Runnable(){public void run(){if(!isFinishing())render(true);}});}catch(RuntimeException failure){android.util.Log.w("OnLoopio","Audio index rebuild failed");}}});
        render(true);
        if((firstResume || changed || imported || reload) && config!=null && online.homeWifi() && !settings.flag("force_offline",false)) {
            if(imported || reload || !store.catalogSynced())refresh(false);
            else {checkMode();PlaylistSyncService.request(this,"startup",accountSwitched,null);}
        }
        firstResume=false;
    }
    protected void onPause() {rememberSelection();unregisterReceiver(synchronizedPlaylists);unregisterReceiver(feedbackUpdated);unregisterReceiver(localUpdated);if(foreground==this)foreground=null;invalidate();main.removeCallbacks(tick);cancelTap();center.cancel();wheelGuard.reset();super.onPause();}
    protected void onDestroy() { invalidate(); worker.shutdownNow();coversWorker.shutdownNow();artworkRequest++; store.close(); super.onDestroy(); }
    private void invalidate() { generation++; if(pending!=null) pending.cancel(true);pending=null; }
    private String trackLabel(Song song) {
        String label=song.title;
        if(settings.flag("file_extensions",false) && song.suffix.length()>0 && !label.endsWith("."+song.suffix)) label+="."+song.suffix;
        return (likedIds.contains(song.id)?"♥ ":"")+(!offline() && (song.local() || audioCache!=null && audioCache.contains(song))?"✓ ":"")+label+(song.artist.length()==0?"":"\n"+song.artist);
    }
    private boolean offline() { return !online.online(); }
    private boolean availableOnly(){return offline();}
    private List<Song> library() {
        List<Song> all=availableOnly()?(audioCache==null?new ArrayList<Song>():store.offlineSongs(audioCache.completedNames())):store.catalogSongs();List<Song> result=new ArrayList<Song>();
        for(Song s:all)if(!availableOnly() || audioCache!=null && audioCache.contains(s))result.add(s);result.addAll(store.localSongs());return result;
    }
    private List<Song> selectionSource(String artist,String album,String genre){if(availableOnly() || !store.catalogSynced())return library();List<Song> source=store.catalogTracks(artist,album,genre);source.addAll(store.localSongs());return source;}
    private List<Song> playable(List<Song> source) {
        if(!offline()) return source; List<Song> result=new ArrayList<Song>();for(Song s:source)if(s.local() && new java.io.File(s.localPath).isFile() || audioCache!=null && audioCache.contains(s))result.add(s);return result;
    }
    private java.util.Set<String> artistAlbums(String key) {
        java.util.Set<String> result=new java.util.HashSet<String>();if(key!=null)for(Library.Entity entity:store.albums())if(key.equals("id:"+entity.artistId))result.add("id:"+entity.id);return result;
    }
    private static String albumNameKey(String artist,String album){return artist.trim().toLowerCase(java.util.Locale.US)+"\u0000"+album.trim().toLowerCase(java.util.Locale.US);}
    private void registerAlbumName(String artist,String album,String key){
        String name=albumNameKey(artist,album),previous=albumKeyByName.get(name);
        albumKeyByName.put(name,previous==null || previous.equals(key)?key:"");
    }
    private String albumKey(Song song){String matched=song.local()?albumKeyByName.get(albumNameKey(song.artist,song.album)):null;return matched==null || matched.length()==0?song.albumKey():matched;}
    private boolean byAlbum(Song song,String key){return key==null || key.equals(albumKey(song));}
    private boolean byArtist(Song song,String key,java.util.Set<String> owned) {return key==null || key.equals(song.artistKey()) || owned.contains(song.albumKey()) || song.local() && song.artist.equalsIgnoreCase(artistNameByKey.get(key));}
    private void albumOrder(List<Song> songs) {Collections.sort(songs,new Comparator<Song>(){public int compare(Song a,Song b){int group=compareNames(a.album,b.album);if(group!=0)return group;if(a.disc!=b.disc)return a.disc-b.disc;if(a.track!=b.track)return a.track-b.track;return compareNames(a.title,b.title);}});}
    private void render(boolean preserve) {
        // Capture against the rows that are actually displayed, before rebuilding their data.
        rememberSelection();
        if(screen==PLAYER){
            renderedList=null;
            if(playerView==null || getWindow().getDecorView().findViewWithTag("player")!=playerView){playerView=new NowPlayingView(this);playerView.setTag("player");setContentView(playerView);playerView.covers(currentCover,nextCover);playerView.requestFocus();}
            playerStatus();return;
        }
        wheelGuard.reset();
        if(playerView!=null && getWindow().getDecorView().findViewWithTag("player")==playerView)createPage();
        if(audioCache!=null)audioCache.refreshIfStale();ListPosition saved=preserve?positions.get(listKey()):null;labels.clear();groups.clear();likedIds=store.likedIds();
        if(screen==HOME) {
            title.setText("OnLoopio"); Collections.addAll(labels,"Playlists","Artists","Albums","Tracks","Genres","Now Playing","Settings","Favorite tracks");
            status.setText(Ui.deviceInfo(this)+" · "+(offline()?"OFFLINE":"ONLINE")+" · "+(offline()?library().size():store.catalogCount()+store.localCount())+" tracks");
        } else if(screen==PLAYLISTS) {
            title.setText("Playlists"); playlists=config==null?new ArrayList<Playlist>():store.playlists();
            if(offline()) { List<Playlist> available=new ArrayList<Playlist>(); for(Playlist p:playlists) {PlaylistDetail d=store.detail(p.id); if(d!=null && !playable(d.songs).isEmpty()) available.add(p);} playlists=available; }
            for(Playlist p:playlists) labels.add((store.followsPlaylist(p.id)?"✓ ":"")+p.name+"   "+p.songCount);
            labels.add("Sync playlist metadata"); labels.add("Back to main menu");
            status.setText(playlists.size()+" playlists · "+(offline()?"offline":"online"));
        } else if(screen==DETAIL) {
            title.setText(current.name); tracks=detail==null?new ArrayList<Song>():playable(detail.songs);
            for(Song song:tracks) labels.add(trackLabel(song));
            labels.add("Keep playlist offline"); labels.add("Refresh this playlist"); labels.add("Back to playlists");
            status.setText(tracks.size()+" tracks · ✓ saved audio");
        } else if(screen==ARTISTS || screen==ALBUMS) {
            List<Song> songs=(!availableOnly() && store.catalogSynced())?store.localSongs():library();TreeSet<String> unique=new TreeSet<String>();albumNames.clear();artistNames.clear();artistNameByKey.clear();artistKeyByName.clear();albumKeyByName.clear();java.util.Set<String> owned=artistAlbums(artistKey);
            if(!availableOnly())for(Library.Entity entity:store.albums())registerAlbumName(entity.artist,entity.name,"id:"+entity.id);
            for(Song song:songs)if(!song.local())registerAlbumName(song.artist,song.album,song.albumKey());
            if(!availableOnly())for(Library.Entity entity:store.artists()){artistNameByKey.put("id:"+entity.id,entity.name);artistKeyByName.put(entity.name.toLowerCase(java.util.Locale.US),"id:"+entity.id);}
            for(Song song:songs)if(!song.local()){artistNameByKey.put(song.artistKey(),song.artist);artistKeyByName.put(song.artist.toLowerCase(java.util.Locale.US),song.artistKey());}
            for(Song song:songs) if(byArtist(song,artistKey,owned)) {String key=song.local()?artistKeyByName.get(song.artist.toLowerCase(java.util.Locale.US)):song.artistKey();if(key==null)key=song.artistKey();String albumGroup=albumKey(song);unique.add(screen==ARTISTS?key:albumGroup);artistNames.put(key,song.artist);artistNameByKey.put(key,song.artist);albumNames.put(albumGroup,song.album.length()==0?"Singles / unknown album":song.album);}
            if(!availableOnly()) {
                if(screen==ARTISTS) {for(Library.Entity entity:store.artists()){String key="id:"+entity.id;unique.add(key);artistNames.put(key,entity.name);}}
                else for(Library.Entity entity:store.albums()) if(artistKey==null || artistKey.equals("id:"+entity.artistId)) {String key="id:"+entity.id;unique.add(key);albumNames.put(key,entity.name);}
            }
            groups.addAll(unique);
            Collections.sort(groups,new Comparator<String>(){public int compare(String a,String b){int result=compareNames(screen==ARTISTS?artistNames.get(a):albumNames.get(a),screen==ARTISTS?artistNames.get(b):albumNames.get(b));return result==0?a.compareTo(b):result;}});
            title.setText(screen==ARTISTS?"Artists":artist==null?"Albums":artist);
            for(String group:groups) labels.add(screen==ARTISTS?(artistNames.get(group).length()==0?"Unknown artist":artistNames.get(group)):albumNames.get(group));
            labels.add(screen==ALBUMS && artist!=null?"All tracks by this artist":"Back to main menu");
            if(screen==ALBUMS && artist!=null) labels.add("Back to artists");
            status.setText(groups.size()+" "+(screen==ARTISTS?"artists":"albums")+" · "+(offline()?"offline":"online"));
        } else if(screen==GENRES) {
            title.setText("Genres");TreeSet<String> unique=new TreeSet<String>();if(!availableOnly() && store.catalogSynced())unique.addAll(store.catalogGenres());for(Song song:availableOnly()?library():store.localSongs())if(song.genre.length()>0)unique.add(song.genre);groups.addAll(unique);sort(groups);labels.addAll(groups);labels.add("Back to main menu");status.setText(groups.size()+" genres · "+(availableOnly()?"on device":"online"));
        } else if(screen==TRACKS || screen==FAVORITES) {
            tracks=new ArrayList<Song>();
            java.util.Set<String> owned=artistAlbums(artistKey);
            List<Song> source=screen==FAVORITES?playable(store.favoriteSongs()):selectionSource(artistKey,album,genre);
            for(Song song:source)if(screen==FAVORITES || (album!=null || byArtist(song,artistKey,owned)) && byAlbum(song,album) && (genre==null || genre.equals(song.genre)))tracks.add(song);
            if(album!=null) albumOrder(tracks);else Collections.sort(tracks,new Comparator<Song>(){public int compare(Song a,Song b){return compareNames(a.title,b.title);}});
            title.setText(screen==FAVORITES?"Favorite tracks":album!=null?(tracks.isEmpty()?"Album":tracks.get(0).album):artist!=null?artist:genre!=null?genre:"Tracks");
            for(Song song:tracks) labels.add(trackLabel(song)); labels.add("Back"); status.setText(tracks.size()+" tracks · ✓ saved audio");
        } else if(screen==PLAYER_MENU) {
            title.setText("Player menu");Collections.addAll(labels,"Library","Download queue","Settings","Power off","Previous","Next","Download this track","Protect from cleanup","Allow rotation");
            Song playing=PlaybackService.state.song;menuSongId=playing==null?null:playing.id;menuAccount=store.accountKey();playerLikeIndex=-1;
            if(menuSongId!=null){playerLikeIndex=labels.size();labels.add(store.isLiked(menuSongId)?"Remove like":"Like");}labels.add("Back");status.setText("Center: open · Back: return to music");
        } else if(screen==POWER_CONFIRM) {
            title.setText("Power off the Y1?");Collections.addAll(labels,"Cancel","Power off");status.setText("Wheel: choose · Centre: apply · Back: cancel");
        } else if(screen==CONTEXT) {
            title.setText(contextTitle); Collections.addAll(labels,"Play now","Play next","Add to playback queue",contextPlaylistId==null?"Download for offline":"Keep playlist offline","Remove downloaded audio","Details","Protect from cleanup","Allow rotation");if(contextPlaylistId!=null)labels.add("Stop following updates");
            contextLikeIndex=-1;if(contextSongId!=null){contextLikeIndex=labels.size();labels.add(store.isLiked(contextSongId)?"Remove like":"Like");}labels.add("Back");
            status.setText(contextTracks.size()+" tracks · "+(offline()?"offline":"online"));
        }
        renderedList=listKey();renderedRows.clear();for(int n=0;n<labels.size();n++)renderedRows.add(selectionKey(n));
        int selected=0;
        if(saved!=null){
            selected=saved.index;int nearest=-1;
            for(int n=0;n<renderedRows.size();n++)if(saved.row.equals(renderedRows.get(n)) && (nearest<0 || Math.abs(n-saved.index)<Math.abs(nearest-saved.index)))nearest=n;
            if(nearest>=0)selected=nearest;
            else if(!saved.row.startsWith("action:") && entityCount()>0)selected=Math.min(selected,entityCount()-1);
        }
        selected=Math.max(0,Math.min(selected,labels.size()-1));
        // Reattaching discards adapter scroll state before restoring this list's own anchor.
        list.setAdapter(adapter);list.requestFocus();list.setSelectionFromTop(selected,saved==null?0:saved.top);
    }
    private String listKey(){
        if(screen==DETAIL)return screen+":"+(current==null?"":current.id);
        if(screen==ALBUMS || screen==TRACKS)return screen+":"+artistKey+":"+album+":"+genre;
        return String.valueOf(screen);
    }
    private void rememberSelection(){if(list!=null)rememberSelection(list.getSelectedItemPosition());}
    private void rememberSelection(int position){
        if(renderedList==null || position<0 || position>=renderedRows.size())return;
        View row=list.getChildAt(position-list.getFirstVisiblePosition());
        positions.put(renderedList,new ListPosition(renderedRows.get(position),position,row==null?0:row.getTop()-list.getPaddingTop()));
    }
    private void closeContext(){screen=previousScreen;if(screen==DETAIL && current==null)screen=PLAYLISTS;render(true);}
    private void activate(int position) {
        if(screen==POWER_CONFIRM){
            if(position==1){screen=PLAYER;render(false);io.onloopio.device.PowerReceiver.powerOff(this);return;}
            screen=PLAYER_MENU;render(true);return;
        }
        if(screen==CONTEXT) {
            if(position==labels.size()-1) {closeContext();return;}
            if(position==contextLikeIndex){like(contextAccount,contextSongId);render(true);return;}
            if(position==8 && contextPlaylistId!=null){store.followPlaylist(contextPlaylistId,false);closeContext();return;}
            if(position==3 && contextPlaylistId!=null){keepOffline(contextPlaylistId,contextTracks);closeContext();return;}
            if(contextTracks.isEmpty()) {status.setText("No tracks in this selection.");return;}
            if(position==0) {PlaybackService.play(this,contextTracks,0,false);screen=PLAYER;}
            else if(position==1) PlaybackService.entityAction(this,PlaybackService.PLAY_NEXT,contextTracks);
            else if(position==2) PlaybackService.entityAction(this,PlaybackService.APPEND,contextTracks);
            else if(position==3) PlaybackService.entityAction(this,PlaybackService.ENQUEUE,contextTracks);
            else if(position==4){if(contextPlaylistId!=null)store.followPlaylist(contextPlaylistId,false);PlaybackService.entityAction(this,PlaybackService.REMOVE,contextTracks);}
            else if(position==6 || position==7){List<String> ids=new ArrayList<String>();for(Song s:contextTracks)ids.add(s.id);store.pin(ids,position==6);status.setText(position==6?"Protected from automatic cleanup":"Eligible for rotation");return;}
            else {int saved=0;for(Song s:contextTracks) if(s.local() && new java.io.File(s.localPath).isFile() || audioCache!=null && audioCache.contains(s)) saved++;
                Song first=contextTracks.get(0);status.setText(contextTitle+"\n"+contextTracks.size()+" tracks · "+saved+" offline"+(contextTracks.size()==1?"\n"+first.artist+" · "+first.album+" · "+first.suffix+" · "+time(first.duration*1000)+(first.local()?"\n"+first.localPath:""):""));return;}
            if(screen==CONTEXT)closeContext();else render(false);return;
        }
        if(screen==HOME) {
            if(position==0) screen=PLAYLISTS;
            else if(position==1) {artist=null;artistKey=null;album=null;genre=null;screen=ARTISTS;}
            else if(position==2) {artist=null;artistKey=null;album=null;genre=null;screen=ALBUMS;}
            else if(position==3) {artist=null;artistKey=null;album=null;genre=null;screen=TRACKS;}
            else if(position==4) {artist=null;artistKey=null;album=null;genre=null;screen=GENRES;}
            else if(position==5) screen=PLAYER;else if(position==7){artist=null;artistKey=null;album=null;genre=null;screen=FAVORITES;}else { settings(); return; }
        } else if(screen==PLAYLISTS) {
            if(position<playlists.size()) {
                current=playlists.get(position); detail=store.detail(current.id); screen=DETAIL; render(false);
                if(detail==null && !offline()) refresh(true); return;
            } else if(position==playlists.size()) { refresh(false); return; } else {onBackPressed();return;}
        } else if(screen==DETAIL) {
            if(position<tracks.size()) { PlaybackService.play(this,tracks,position,false); screen=PLAYER; }
            else if(position==tracks.size()) {
                keepOffline(current.id,tracks);startActivity(new Intent(this,DownloadsActivity.class));return;
            } else if(position==tracks.size()+1) { refresh(true); return; } else {onBackPressed();return;}
        } else if(screen==ARTISTS) {
            if(position<groups.size()) {artistKey=groups.get(position);artist=artistNames.get(artistKey);album=null;screen=ALBUMS;}else {onBackPressed();return;}
        } else if(screen==ALBUMS) {
            if(position<groups.size()) { album=groups.get(position); screen=TRACKS; }
            else if(artist!=null && position==groups.size()) { album=null; screen=TRACKS; }
            else {onBackPressed();return;}
        } else if(screen==GENRES) {
            if(position<groups.size()){genre=groups.get(position);artist=null;artistKey=null;album=null;screen=TRACKS;}else {onBackPressed();return;}
        } else if(screen==TRACKS || screen==FAVORITES) {
            if(position<tracks.size()) { PlaybackService.play(this,tracks,position,false); screen=PLAYER; }
            else { onBackPressed(); return; }
        } else if(screen==PLAYER_MENU) {
            if(position==playerLikeIndex){like(menuAccount,menuSongId);render(true);return;}
            if(position==0)screen=HOME;
            else if(position==1){startActivity(new Intent(this,DownloadsActivity.class));return;}
            else if(position==2){settings();return;}
            else if(position==3)screen=POWER_CONFIRM;
            else if(position==4 || position==5){PlaybackService.action(this,position==4?PlaybackService.PREVIOUS:PlaybackService.NEXT);screen=PLAYER;}
            else if(position==6){if(PlaybackService.state.song!=null)PlaybackService.entityAction(this,PlaybackService.ENQUEUE,Collections.singletonList(PlaybackService.state.song));startActivity(new Intent(this,DownloadsActivity.class));return;}
            else if(position==7 || position==8){if(PlaybackService.state.song!=null)store.pin(Collections.singletonList(PlaybackService.state.song.id),position==7);screen=PLAYER;}
            else screen=PLAYER;
        }
        render(false);
    }
    private void settings() { startActivity(new Intent(this,SettingsActivity.class)); }
    private void like(String account,String id){if(id==null || ControlLock.locked(this))return;try{boolean liked=store.toggleLike(account,id);android.widget.Toast.makeText(this,Ui.label(this,liked?"Liked":"Like removed"),android.widget.Toast.LENGTH_SHORT).show();PlaylistSyncService.feedbackChanged(this);}catch(IllegalStateException changed){status.setText("Account changed");}}
    private void keepOffline(String id,List<Song> songs){store.followPlaylist(id,true);if(!songs.isEmpty())PlaybackService.entityAction(this,PlaybackService.ENQUEUE,songs);PlaylistSyncService.request(this,"manual",false,id);}
    private void cancelTap() {if(pendingTap!=null) main.removeCallbacks(pendingTap);pendingTap=null;lastTapPosition=-1;}
    private void tapped(final int position) {
        if(list.getSelectedItemPosition()!=position)list.setSelection(position);rememberSelection(position);
        long now=android.os.SystemClock.uptimeMillis();
        if(pendingTap!=null && position==lastTapPosition && now-lastTapAt<420) {cancelTap();playEntity(position);return;}
        cancelTap(); lastTapAt=now;lastTapPosition=position;
        pendingTap=new Runnable(){public void run(){pendingTap=null;lastTapPosition=-1;activate(position);}};
        main.postDelayed(pendingTap,420);
    }
    private List<Song> entityTracks(int position) {
        List<Song> selected=new ArrayList<Song>();List<Song> all=null;
        if(screen==PLAYLISTS && position<playlists.size()) {PlaylistDetail d=store.detail(playlists.get(position).id);return d==null?selected:playable(d.songs);}
        if(screen==DETAIL && position<tracks.size()) {selected.add(tracks.get(position));return selected;}
        if(screen==ARTISTS && position<groups.size()){String key=groups.get(position);all=selectionSource(key,null,null);java.util.Set<String> owned=artistAlbums(key);for(Song song:all)if(byArtist(song,key,owned))selected.add(song);albumOrder(selected);return selected;}
        if(screen==ALBUMS && position<groups.size()){String key=groups.get(position);all=selectionSource(null,key,null);for(Song song:all)if(byAlbum(song,key))selected.add(song);albumOrder(selected);return selected;}
        if(screen==GENRES && position<groups.size()){String name=groups.get(position);all=selectionSource(null,null,name);for(Song song:all)if(name.equals(song.genre))selected.add(song);return selected;}
        if((screen==TRACKS || screen==FAVORITES) && position<tracks.size()) {selected.add(tracks.get(position));return selected;}
        return selected;
    }
    private boolean entityPosition(int position) {return position>=0 && ((screen==PLAYLISTS && position<playlists.size()) || (screen==DETAIL && position<tracks.size()) || (screen==ARTISTS && position<groups.size()) || (screen==ALBUMS && position<groups.size()) || (screen==GENRES && position<groups.size()) || ((screen==TRACKS || screen==FAVORITES) && position<tracks.size()));}
    private String selectionKey(int position){
        if(position<0)return "";
        if(screen==PLAYER)return "player";
        if(screen==PLAYLISTS && position<playlists.size())return "playlist:"+playlists.get(position).id;
        if((screen==DETAIL || screen==TRACKS || screen==FAVORITES) && position<tracks.size())return "song:"+tracks.get(position).id;
        if((screen==ARTISTS || screen==ALBUMS || screen==GENRES) && position<groups.size())return "group:"+groups.get(position);
        return position<labels.size()?"action:"+(position-entityCount()):"";
    }
    private int entityCount(){return screen==PLAYLISTS?playlists.size():screen==DETAIL || screen==TRACKS || screen==FAVORITES?tracks.size():screen==ARTISTS || screen==ALBUMS || screen==GENRES?groups.size():0;}
    private boolean sameCenterSelection(){return screen==centerScreen && centerRow.equals(selectionKey(centerPosition));}
    private void playEntity(int position) {if(!entityPosition(position)){activate(position);return;}List<Song> songs=entityTracks(position);if(songs.isEmpty()){status.setText("No available tracks in this selection.");return;}PlaybackService.play(this,songs,0,false);screen=PLAYER;render(false);}
    private void openContext(int position) {
        cancelTap(); if(!entityPosition(position)) return;
        if(list.getSelectedItemPosition()!=position)list.setSelection(position);rememberSelection(position);
        contextSongId=(screen==DETAIL || screen==TRACKS || screen==FAVORITES)?tracks.get(position).id:null;contextAccount=store.accountKey();
        contextPlaylistId=screen==PLAYLISTS?playlists.get(position).id:null;contextTracks=entityTracks(position); contextTitle=labels.get(position).split("\\n")[0]; previousScreen=screen;screen=CONTEXT;render(false);
    }
    private void refresh(boolean one) {
        if(config==null) { status.setText("Configure Navidrome over USB first."); return; }
        if(!online.homeWifi() || settings.flag("force_offline",false)) { online.failed();render(true);return; }
        lastRefreshAttempt=android.os.SystemClock.elapsedRealtime();PlaylistSyncService.request(this,"manual",!one,one && current!=null?current.id:null);status.setText("Synchronizing Navidrome playlists…");
    }
    private void checkMode() {
        if(config==null || !online.homeWifi() || settings.flag("force_offline",false)) {online.failed();render(true);return;}
        invalidate();lastRefreshAttempt=android.os.SystemClock.elapsedRealtime();final int request=generation;final ServerConfig server=config;
        pending=worker.submit(new Runnable(){public void run(){final boolean success=online.check(server);main.post(new Runnable(){public void run(){
            if(request!=generation || isFinishing())return;pending=null;render(true);
            if(!success)status.setText("OFFLINE · Navidrome unavailable");else if(store.pendingDownloads()>0) PlaybackService.action(PlaylistActivity.this,PlaybackService.KICK);
        }});}});
    }
    static String safeMessage(Exception error) {
        if(error instanceof io.onloopio.api.ApiException) return error.getMessage();
        if(error instanceof javax.net.ssl.SSLException) return "HTTPS failed. Check time and CA.";
        if(error instanceof java.net.UnknownHostException) return "Server name cannot be resolved.";
        if(error instanceof java.net.SocketTimeoutException) return "Server timed out.";
        return "Cannot connect. Check Wi-Fi and server settings.";
    }
    public void onBackPressed() {
        if(ControlLock.locked(this))return;center.cancel();wheelGuard.reset();
        cancelTap(); if(screen==PLAYER)return;else if(screen==PLAYER_MENU)screen=PLAYER;else if(screen==POWER_CONFIRM)screen=PLAYER_MENU;else if(screen==CONTEXT){closeContext();return;}
        else if(screen==DETAIL) screen=PLAYLISTS;
        else if(screen==FAVORITES)screen=HOME;
        else if(screen==TRACKS && genre!=null) {genre=null;screen=GENRES;}
        else if(screen==TRACKS && album!=null) { album=null; screen=ALBUMS; }
        else if(screen==TRACKS && artist!=null) screen=ALBUMS;
        else if(screen==ALBUMS && artist!=null) {artist=null;artistKey=null;screen=ARTISTS;}
        else screen=screen==HOME?PLAYER:HOME;
        render(true);
    }
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code=event.getKeyCode();
        if(Y1Keys.select(code)){center.key(event);return true;}
        if(ControlLock.locked(this) && ControlLock.blocks(code)){center.cancel();return true;}
        if(screen==PLAYER && (Y1Keys.previousRow(code)||Y1Keys.nextRow(code)))center.finishBeforeWheel();else center.cancel();
        if(screen==PLAYER && (Y1Keys.previousRow(code)||Y1Keys.nextRow(code))){
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0){
                settings.feedback();int direction=wheelGuard.turn(Y1Keys.previousRow(code)?-1:1,android.os.SystemClock.uptimeMillis(),wheelContext());
                if(direction!=0){
                    if(wheelSeeking)startService(new Intent(this,PlaybackService.class).setAction(PlaybackService.SEEK).putExtra("seconds",direction*5));
                    else ((android.media.AudioManager)getSystemService(AUDIO_SERVICE)).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,direction>0?android.media.AudioManager.ADJUST_RAISE:android.media.AudioManager.ADJUST_LOWER,0);
                }
                playerStatus();
            }return true;
        }
        wheelGuard.reset();
        if(code==KeyEvent.KEYCODE_MENU && screen==PLAYER){if(event.getAction()==KeyEvent.ACTION_UP)playerMenu();return true;}
        if(code==KeyEvent.KEYCODE_MENU) { if(event.getAction()==KeyEvent.ACTION_UP) settings(); return true; }
        if(Y1Keys.previousRow(code) || Y1Keys.nextRow(code)) {
            cancelTap();
            if(event.getAction()==KeyEvent.ACTION_DOWN) settings.feedback();
            int normalized=Y1Keys.previousRow(code)?KeyEvent.KEYCODE_DPAD_UP:KeyEvent.KEYCODE_DPAD_DOWN;
            return super.dispatchKeyEvent(new KeyEvent(event.getDownTime(),event.getEventTime(),event.getAction(),normalized,event.getRepeatCount()));
        }
        if(MediaButtons.handle(this,event))return true;
        return super.dispatchKeyEvent(event);
    }
    public void openLibrary(){if(ControlLock.locked(this))return;center.cancel();screen=HOME;render(false);}
    private void playerMenu(){cancelTap();wheelGuard.reset();screen=PLAYER_MENU;render(false);}
    private String wheelContext(){if(!wheelSeeking)return "volume";PlaybackService.State state=PlaybackService.state;return state.song==null?null:state.song.id+":"+state.queuePosition;}
    private void importControls(){if(io.onloopio.device.ControlConfigStore.importPrivateFile(this)){center.cancel();wheelGuard=new WheelTurnGuard(settings.number("wheel_steps_per_turn",WheelTurnGuard.DEFAULT_STEPS_PER_TURN));}}
    public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused && center!=null){center.cancel();wheelGuard.reset();}}
    private void playerStatus() {
        final PlaybackService.State state=PlaybackService.state;wheelGuard.expire(android.os.SystemClock.uptimeMillis(),wheelContext());if(playerView!=null){android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);playerView.controls(ControlLock.locked(this),wheelSeeking,wheelGuard.armed(),wheelGuard.progress(),audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));playerView.update(state);}
        final Song current=state.song,next=state.next;final ServerConfig server=config;
        String key=(server==null?"":server.accountKey())+":"+(current==null?"":current.id+current.coverArt)+":"+(next==null?"":next.id+next.coverArt)+":"+(!offline());
        if(key.equals(artworkKey))return;artworkKey=key;final int request=++artworkRequest;final boolean network=!offline();
        currentCover=null;nextCover=null;if(playerView!=null)playerView.covers(null,null);
        coversWorker.submit(new Runnable(){public void run(){Bitmap a=null,b=null;try{CoverCache cache=new CoverCache(PlaylistActivity.this,server);NavidromeClient client=server==null?null:new NavidromeClient(server);try{a=cache.load(current,client,network);}catch(Exception missing){}try{b=cache.load(next,client,network);}catch(Exception missing){}}catch(Exception missing){}
            final Bitmap cover=a,preview=b;main.post(new Runnable(){public void run(){if(request!=artworkRequest || isFinishing())return;currentCover=cover;nextCover=preview;if(playerView!=null)playerView.covers(cover,preview);}});
        }});
    }
    private static String time(int ms) { return (ms/60000)+":"+String.format(java.util.Locale.US,"%02d",(ms/1000)%60); }
    private final Runnable tick=new Runnable() { long lastMode; public void run() {
        if(screen==PLAYER) playerStatus();
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastMode>5000){lastMode=now;
            if(config!=null && !settings.flag("force_offline",false) && online.homeWifi() && (online.needsCheck() || !online.online()) && pending==null && now-lastRefreshAttempt>30000) {
                checkMode();
            }
            else if(!online.homeWifi() && screen!=PLAYER && screen!=CONTEXT) render(true);
        } main.postDelayed(this,500); } };
    private int compareNames(String a,String b) {
        int result=settings.flag("natural_sort",true)?natural(a,b):a.compareToIgnoreCase(b);
        return settings.flag("sort_descending",false)?-result:result;
    }
    private void sort(List<String> names) { Collections.sort(names,new Comparator<String>() { public int compare(String a,String b) { return compareNames(a,b); } }); }
    static int natural(String a,String b) {
        int i=0,j=0;
        while(i<a.length() && j<b.length()) {
            char x=Character.toLowerCase(a.charAt(i)),y=Character.toLowerCase(b.charAt(j));
            if(Character.isDigit(x) && Character.isDigit(y)) {
                int endA=i,endB=j; while(endA<a.length() && Character.isDigit(a.charAt(endA))) endA++; while(endB<b.length() && Character.isDigit(b.charAt(endB))) endB++;
                String n=a.substring(i,endA).replaceFirst("^0+(?!$)",""),m=b.substring(j,endB).replaceFirst("^0+(?!$)","");
                int result=n.length()==m.length()?n.compareTo(m):n.length()-m.length(); if(result!=0) return result; i=endA; j=endB;
            } else { if(x!=y) return x-y; i++; j++; }
        }
        return (a.length()-i)-(b.length()-j);
    }
    private final class RowAdapter extends BaseAdapter {
        public int getCount() { return labels.size(); } public Object getItem(int position) { return labels.get(position); } public long getItemId(int position) { return position; }
        public View getView(int position,View recycled,ViewGroup parent) { TextView row=recycled instanceof TextView?(TextView)recycled:Ui.row(PlaylistActivity.this,""); row.setText(labels.get(position)); return row; }
    }
}
