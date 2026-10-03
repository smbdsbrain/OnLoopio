package io.onloopio.library;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.onloopio.db.MetadataStore;
import io.onloopio.model.Library;
import io.onloopio.model.Song;
import io.onloopio.player.AudioCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-scoped, screen-independent view of the music library. Screens only filter and label its lists.
 * A snapshot is built on a worker whenever the store, the audio cache, the online mode or the sort order changed;
 * callers get the latest snapshot at once (possibly stale, or null before the first build) and a callback when a fresh one lands.
 */
public final class LibraryModel {
    public interface Listener { void libraryModelReady(); }
    public static final class Snapshot {
        final String key;
        public final boolean offline,catalogSynced;
        /** Catalog (filtered to saved audio when offline) plus local music; byTitle is the same list sorted for the Tracks screen. */
        public final List<Song> library,localSongs,byTitle;
        /** Songs that drive artist and album grouping, as the screens defined it: local music when the catalog is synced online, otherwise the whole library. */
        public final List<Song> groupingSongs;
        public final List<Library.Entity> albums,artists;
        public final List<String> genres;
        public final Map<String,String> albumKeyByName,artistNameByKey,artistKeyByName;
        public final int trackCount,buildMs;
        Snapshot(String key,boolean offline,boolean catalogSynced,List<Song> library,List<Song> localSongs,List<Song> byTitle,List<Song> groupingSongs,List<Library.Entity> albums,List<Library.Entity> artists,List<String> genres,Map<String,String> albumKeyByName,Map<String,String> artistNameByKey,Map<String,String> artistKeyByName,int trackCount,int buildMs){
            this.key=key;this.offline=offline;this.catalogSynced=catalogSynced;this.library=library;this.localSongs=localSongs;this.byTitle=byTitle;this.groupingSongs=groupingSongs;this.albums=albums;this.artists=artists;this.genres=genres;this.albumKeyByName=albumKeyByName;this.artistNameByKey=artistNameByKey;this.artistKeyByName=artistKeyByName;this.trackCount=trackCount;this.buildMs=buildMs;
        }
    }
    private static LibraryModel instance;
    public static synchronized LibraryModel get(Context context){if(instance==null)instance=new LibraryModel(context.getApplicationContext());return instance;}

    private final Context app;private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Snapshot snapshot;private String building;private final List<Listener> listeners=new ArrayList<Listener>();
    private LibraryModel(Context app){this.app=app;}

    public static String albumNameKey(String artist,String album){return artist.trim().toLowerCase(Locale.US)+"\u0000"+album.trim().toLowerCase(Locale.US);}

    /** Latest snapshot for these inputs, or the previous one while a fresh build runs, or null before the first build. */
    public synchronized Snapshot request(final boolean offline,final AudioCache cache,final int sortMode,final Comparator<String> names,Listener listener){
        final String key=offline+"|"+(cache==null?"-":Integer.toHexString(System.identityHashCode(cache)))+"|"+MetadataStore.changes+"|"+AudioCache.changes+"|"+sortMode;
        Snapshot current=snapshot;
        if(listener!=null && !listeners.contains(listener))listeners.add(listener);
        if((current==null || !current.key.equals(key)) && !key.equals(building)){
            building=key;
            worker.submit(new Runnable(){public void run(){
                Snapshot built=null;
                try{built=build(key,offline,cache,names);}catch(RuntimeException failure){Log.w("OnLoopio","LIBRARY_MODEL_FAILED",failure);}
                final Snapshot result=built;
                synchronized(LibraryModel.this){if(key.equals(building))building=null;if(result!=null)snapshot=result;}
                if(result!=null)main.post(new Runnable(){public void run(){List<Listener> targets;synchronized(LibraryModel.this){targets=new ArrayList<Listener>(listeners);}for(Listener l:targets)l.libraryModelReady();}});
            }});
        }
        return current;
    }
    public synchronized void forget(Listener listener){listeners.remove(listener);}

    private Snapshot build(String key,boolean offline,AudioCache cache,final Comparator<String> names){
        long started=android.os.SystemClock.uptimeMillis();MetadataStore store=new MetadataStore(app);
        try{
            boolean synced=store.catalogSynced();
            List<Song> local=store.localSongs();
            List<Song> all=offline?(cache==null?new ArrayList<Song>():store.offlineSongs(cache.completedNames())):store.catalogSongs();
            List<Song> library=new ArrayList<Song>(all.size()+local.size());
            for(Song s:all)if(!offline || cache!=null && cache.contains(s))library.add(s);
            library.addAll(local);
            List<Song> byTitle=new ArrayList<Song>(library);
            Collections.sort(byTitle,new Comparator<Song>(){public int compare(Song a,Song b){return names.compare(a.title,b.title);}});
            List<Song> grouping=(!offline && synced)?local:library;
            List<Library.Entity> albums=offline?Collections.<Library.Entity>emptyList():store.albums(),artists=offline?Collections.<Library.Entity>emptyList():store.artists();
            Map<String,String> albumKeyByName=new HashMap<String,String>(),artistNameByKey=new HashMap<String,String>(),artistKeyByName=new HashMap<String,String>();
            for(Library.Entity e:albums)registerAlbumName(albumKeyByName,e.artist,e.name,"id:"+e.id);
            for(Song s:grouping)if(!s.local())registerAlbumName(albumKeyByName,s.artist,s.album,s.albumKey());
            for(Library.Entity e:artists){artistNameByKey.put("id:"+e.id,e.name);artistKeyByName.put(e.name.toLowerCase(Locale.US),"id:"+e.id);}
            for(Song s:grouping)if(!s.local()){artistNameByKey.put(s.artistKey(),s.artist);artistKeyByName.put(s.artist.toLowerCase(Locale.US),s.artistKey());}
            TreeSet<String> unique=new TreeSet<String>();if(!offline && synced)unique.addAll(store.catalogGenres());
            for(Song s:offline?library:local)if(s.genre.length()>0)unique.add(s.genre);
            List<String> genres=new ArrayList<String>(unique);Collections.sort(genres,names);
            int count=offline?library.size():store.catalogCount()+store.localCount();
            int ms=(int)(android.os.SystemClock.uptimeMillis()-started);
            Log.d("OnLoopio","LIBRARY_MODEL built in "+ms+"ms: tracks="+library.size()+" local="+local.size()+" offline="+offline+" synced="+synced);
            return new Snapshot(key,offline,synced,library,local,byTitle,grouping,albums,artists,genres,albumKeyByName,artistNameByKey,artistKeyByName,count,ms);
        }finally{store.close();}
    }
    private static void registerAlbumName(Map<String,String> albumKeyByName,String artist,String album,String key){
        String name=albumNameKey(artist,album),previous=albumKeyByName.get(name);
        albumKeyByName.put(name,previous==null || previous.equals(key)?key:"");
    }
}
