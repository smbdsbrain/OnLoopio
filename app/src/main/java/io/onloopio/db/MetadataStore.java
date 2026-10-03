package io.onloopio.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Song;
import io.onloopio.model.Library;
import io.onloopio.model.CacheKey;
import io.onloopio.model.LikeChange;
import io.onloopio.model.ListenEvent;
import io.onloopio.sync.BacksyncEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Playlist metadata and its deduplicated library. Audio files live separately on SD. */
public final class MetadataStore extends SQLiteOpenHelper implements BacksyncEngine.Repository {
    /** Bumped after every committed write so app-scoped caches (LibraryModel) know when to rebuild; never before the data lands. */
    public static volatile int changes;
    private static void changed(){changes++;}
    public MetadataStore(Context context) { super(context, "onloopio.db", null, 9); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE server (id INTEGER PRIMARY KEY CHECK(id=1), account_key TEXT NOT NULL, last_successful_sync INTEGER NOT NULL DEFAULT 0, catalog_synced INTEGER NOT NULL DEFAULT 0, playlist_checked_at INTEGER NOT NULL DEFAULT 0, playlist_audited_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE playlist (id TEXT PRIMARY KEY, name TEXT NOT NULL, changed TEXT NOT NULL, song_count INTEGER NOT NULL, duration INTEGER NOT NULL, detail_cached INTEGER NOT NULL DEFAULT 0, offline_sync INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE song (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, suffix TEXT NOT NULL, duration INTEGER NOT NULL, album_id TEXT NOT NULL DEFAULT '', track INTEGER NOT NULL DEFAULT 0, artist_id TEXT NOT NULL DEFAULT '', genre TEXT NOT NULL DEFAULT '', disc INTEGER NOT NULL DEFAULT 0, in_catalog INTEGER NOT NULL DEFAULT 0, audio_name TEXT NOT NULL DEFAULT '', cover_art TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX song_audio_name ON song(audio_name)");
        db.execSQL("CREATE TABLE playlist_song (playlist_id TEXT NOT NULL, song_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlist_id,position))");
        db.execSQL("CREATE TABLE artist (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        db.execSQL("CREATE TABLE album (id TEXT PRIMARY KEY, name TEXT NOT NULL, artist_id TEXT NOT NULL, artist TEXT NOT NULL)");
        db.execSQL("CREATE TABLE download_queue (song_id TEXT PRIMARY KEY, position INTEGER NOT NULL, state INTEGER NOT NULL DEFAULT 0, received INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT -1, error TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL DEFAULT 0)");
        audioState(db);
        localTable(db);
        feedbackTables(db);
    }
    private static void feedbackTables(SQLiteDatabase db){
        db.execSQL("CREATE TABLE track_like(account_key TEXT NOT NULL,song_id TEXT NOT NULL,liked INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account_key,song_id))");
        db.execSQL("CREATE TABLE listen_event(session_id TEXT PRIMARY KEY,account_key TEXT NOT NULL,song_id TEXT NOT NULL,listened_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX listen_event_account ON listen_event(account_key,listened_at,session_id)");
    }
    private static void audioState(SQLiteDatabase db){db.execSQL("CREATE TABLE audio_state (song_id TEXT PRIMARY KEY, downloaded_at INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0, play_count INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0)");}
    private static void localTable(SQLiteDatabase db){db.execSQL("CREATE TABLE local_song(id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,suffix TEXT NOT NULL,duration INTEGER NOT NULL,album_id TEXT NOT NULL,track INTEGER NOT NULL,artist_id TEXT NOT NULL,genre TEXT NOT NULL,disc INTEGER NOT NULL,cover_art TEXT NOT NULL,path TEXT NOT NULL UNIQUE,bytes INTEGER NOT NULL,modified INTEGER NOT NULL)");}
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion==1) { db.execSQL("ALTER TABLE song ADD COLUMN album_id TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE song ADD COLUMN track INTEGER NOT NULL DEFAULT 0"); oldVersion=2; }
        if(oldVersion==2 && newVersion>=3) {
            db.execSQL("ALTER TABLE song ADD COLUMN artist_id TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE song ADD COLUMN genre TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE song ADD COLUMN disc INTEGER NOT NULL DEFAULT 0"); db.execSQL("ALTER TABLE song ADD COLUMN in_catalog INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE artist (id TEXT PRIMARY KEY, name TEXT NOT NULL)"); db.execSQL("CREATE TABLE album (id TEXT PRIMARY KEY, name TEXT NOT NULL, artist_id TEXT NOT NULL, artist TEXT NOT NULL)");
            db.execSQL("CREATE TABLE download_queue (song_id TEXT PRIMARY KEY, position INTEGER NOT NULL, state INTEGER NOT NULL DEFAULT 0)"); oldVersion=3;
        }
        if(oldVersion==3 && newVersion>=4) {Cursor c=db.rawQuery("PRAGMA table_info(server)",null);boolean present=false;try{while(c.moveToNext())if("catalog_synced".equals(c.getString(1)))present=true;}finally{c.close();}if(!present)db.execSQL("ALTER TABLE server ADD COLUMN catalog_synced INTEGER NOT NULL DEFAULT 0");oldVersion=4;}
        if(oldVersion==4 && newVersion>=5){db.execSQL("ALTER TABLE song ADD COLUMN audio_name TEXT NOT NULL DEFAULT ''");db.execSQL("CREATE INDEX song_audio_name ON song(audio_name)");oldVersion=5;}
        if(oldVersion==5 && newVersion>=6){
            db.execSQL("ALTER TABLE song ADD COLUMN cover_art TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_queue ADD COLUMN received INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE download_queue ADD COLUMN total INTEGER NOT NULL DEFAULT -1");
            db.execSQL("ALTER TABLE download_queue ADD COLUMN error TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE download_queue ADD COLUMN updated INTEGER NOT NULL DEFAULT 0");audioState(db);db.execSQL("UPDATE server SET last_successful_sync=0");oldVersion=6;
        }
        if(oldVersion==6 && newVersion>=7){db.execSQL("ALTER TABLE playlist ADD COLUMN offline_sync INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE server ADD COLUMN playlist_checked_at INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE server ADD COLUMN playlist_audited_at INTEGER NOT NULL DEFAULT 0");oldVersion=7;}
        if(oldVersion==7 && newVersion>=8){localTable(db);oldVersion=8;}
        if(oldVersion==8 && newVersion>=9){feedbackTables(db);oldVersion=9;}
        if(oldVersion==newVersion)return;
        throw new IllegalStateException("No migration defined for database version " + oldVersion);
    }
    public synchronized void selectAccount(String key) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor cursor = db.rawQuery("SELECT account_key FROM server WHERE id=1", null);
        String existing = null;
        try { if (cursor.moveToFirst()) existing = cursor.getString(0); } finally { cursor.close(); }
        if (key.equals(existing)) return;
        db.beginTransaction();
        try {
            db.delete("playlist_song", null, null); db.delete("song", null, null); db.delete("playlist", null, null); db.delete("artist",null,null); db.delete("album",null,null); db.delete("download_queue",null,null); db.delete("audio_state","song_id NOT LIKE 'local:%'",null); db.delete("server", null, null);
            ContentValues values = new ContentValues(); values.put("id", 1); values.put("account_key", key);
            db.insertOrThrow("server", null, values); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    changed();}
    public synchronized List<Playlist> playlists() {
        List<Playlist> playlists = new ArrayList<Playlist>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,name,changed,song_count,duration FROM playlist ORDER BY name COLLATE NOCASE,id", null);
        try { while(c.moveToNext()) playlists.add(new Playlist(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getLong(4))); }
        finally { c.close(); }
        return playlists;
    }
    public synchronized long lastRefresh() {
        Cursor c = getReadableDatabase().rawQuery("SELECT last_successful_sync FROM server WHERE id=1", null);
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }
    public synchronized String accountKey(){Cursor c=getReadableDatabase().rawQuery("SELECT account_key FROM server WHERE id=1",null);try{return c.moveToFirst()?c.getString(0):null;}finally{c.close();}}
    public synchronized long lastPlaylistCheck(){return playlistStamp("playlist_checked_at");}
    public synchronized long lastPlaylistAudit(){return playlistStamp("playlist_audited_at");}
    private long playlistStamp(String column){Cursor c=getReadableDatabase().rawQuery("SELECT "+column+" FROM server WHERE id=1",null);try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}}
    public synchronized boolean followsPlaylist(String id){Cursor c=getReadableDatabase().rawQuery("SELECT offline_sync FROM playlist WHERE id=?",new String[]{id});try{return c.moveToFirst() && c.getInt(0)!=0;}finally{c.close();}}
    public synchronized boolean hasPlaylistDetail(String id){Cursor c=getReadableDatabase().rawQuery("SELECT detail_cached FROM playlist WHERE id=?",new String[]{id});try{return c.moveToFirst() && c.getInt(0)!=0;}finally{c.close();}}
    public synchronized boolean hasCatalogSong(String id){Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM song WHERE id=? AND in_catalog=1",new String[]{id});try{return c.moveToFirst();}finally{c.close();}}
    public synchronized void followPlaylist(String id,boolean follow){ContentValues v=new ContentValues();v.put("offline_sync",follow?1:0);getWritableDatabase().update("playlist",v,"id=?",new String[]{id});changed();}
    public synchronized void stopFollowingPlaylists(){getWritableDatabase().execSQL("UPDATE playlist SET offline_sync=0");changed();}
    public synchronized boolean protectedFromCleanup(String id){Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM audio_state WHERE song_id=? AND pinned=1 UNION ALL SELECT 1 FROM playlist_song ps JOIN playlist p ON p.id=ps.playlist_id WHERE ps.song_id=? AND p.offline_sync=1 LIMIT 1",new String[]{id,id});try{return c.moveToFirst();}finally{c.close();}}
    /** Headers, ordered details, catalog (if fetched), queue additions and success stamp commit together. */
    public synchronized int applyPlaylistSync(String account,List<Playlist> headers,List<PlaylistDetail> details,Library catalog,List<String> completedNames,long now,boolean audit){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            if(!account.equals(accountKey()))throw new IllegalStateException("Account changed during synchronization");
            replacePlaylists(headers);for(PlaylistDetail detail:details)saveDetail(detail);if(catalog!=null)replaceCatalog(catalog);
            Set<String> completed=new HashSet<String>(completedNames);List<String> missing=new ArrayList<String>();
            Cursor c=db.rawQuery("SELECT DISTINCT s.id,s.audio_name FROM playlist p JOIN playlist_song ps ON p.id=ps.playlist_id JOIN song s ON s.id=ps.song_id WHERE p.offline_sync=1",null);
            try{while(c.moveToNext())if(!completed.contains(c.getString(1)))missing.add(c.getString(0));}finally{c.close();}
            int before=pendingDownloads();enqueueDownloads(missing);int added=pendingDownloads()-before;
            ContentValues v=new ContentValues();v.put("playlist_checked_at",now);if(audit)v.put("playlist_audited_at",now);db.update("server",v,"id=1",null);
            db.setTransactionSuccessful();return added;
        }finally{db.endTransaction();}
    }
    public synchronized boolean catalogSynced() {
        Cursor c=getReadableDatabase().rawQuery("SELECT catalog_synced FROM server WHERE id=1",null);
        try {return c.moveToFirst() && c.getInt(0)!=0;} finally {c.close();}
    }
    /** Full catalog; playlist-only entries stay visible until the first full sync. */
    public synchronized List<Song> songs() {
        List<Song> result = new ArrayList<Song>();
        Cursor c = getReadableDatabase().rawQuery("SELECT s.id,s.title,s.artist,s.album,s.suffix,s.duration,s.album_id,s.track,s.artist_id,s.genre,s.disc,s.cover_art FROM song s ORDER BY s.title COLLATE NOCASE,s.id",null);
        try { while(c.moveToNext()) result.add(readSong(c)); }
        finally { c.close(); }
        return result;
    }
    public synchronized List<Song> catalogSongs() {
        Cursor flag=getReadableDatabase().rawQuery("SELECT catalog_synced FROM server WHERE id=1",null); boolean ready=false;
        try {ready=flag.moveToFirst() && flag.getInt(0)!=0;} finally {flag.close();}
        if(!ready) return songs();
        List<Song> result=new ArrayList<Song>(); Cursor c=getReadableDatabase().rawQuery("SELECT id,title,artist,album,suffix,duration,album_id,track,artist_id,genre,disc,cover_art FROM song WHERE in_catalog=1 ORDER BY title COLLATE NOCASE,id",null);
        try {while(c.moveToNext()) result.add(readSong(c));} finally {c.close();} return result;
    }
    public synchronized int catalogCount(){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM song"+(catalogSynced()?" WHERE in_catalog=1":""),null);try{c.moveToFirst();return c.getInt(0);}finally{c.close();}}
    public synchronized List<String> catalogGenres(){List<String> result=new ArrayList<String>();Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT genre FROM song WHERE in_catalog=1 AND genre<>'' ORDER BY genre COLLATE NOCASE",null);try{while(c.moveToNext())result.add(c.getString(0));}finally{c.close();}return result;}
    public synchronized List<Song> catalogTracks(String artistKey,String albumKey,String genre){
        StringBuilder sql=new StringBuilder("SELECT id,title,artist,album,suffix,duration,album_id,track,artist_id,genre,disc,cover_art FROM song WHERE in_catalog=1");List<String> args=new ArrayList<String>();
        if(albumKey!=null && albumKey.startsWith("id:")){sql.append(" AND album_id=?");args.add(albumKey.substring(3));}
        else if(artistKey!=null && artistKey.startsWith("id:")){sql.append(" AND (artist_id=? OR album_id IN (SELECT id FROM album WHERE artist_id=?))");args.add(artistKey.substring(3));args.add(artistKey.substring(3));}
        if(genre!=null){sql.append(" AND genre=?");args.add(genre);}sql.append(" ORDER BY title COLLATE NOCASE,id");
        List<Song> result=new ArrayList<Song>();Cursor c=getReadableDatabase().rawQuery(sql.toString(),args.toArray(new String[args.size()]));try{while(c.moveToNext())result.add(readSong(c));}finally{c.close();}return result;
    }
    public synchronized boolean needsAudioIndex(){Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM song WHERE audio_name='' LIMIT 1",null);try{return c.moveToFirst();}finally{c.close();}}
    public synchronized void backfillAudioIndex(){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{Cursor c=db.rawQuery("SELECT id FROM song WHERE audio_name=''",null);List<String> ids=new ArrayList<String>();try{while(c.moveToNext())ids.add(c.getString(0));}finally{c.close();}
            for(String id:ids){ContentValues v=new ContentValues();v.put("audio_name",CacheKey.audioName(id));db.update("song",v,"id=?",new String[]{id});}db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    changed();}
    /** Indexed lookup of actual files, avoiding 9,000 SD stats or hashes on the UI thread. */
    public synchronized List<Song> offlineSongs(List<String> names){
        List<Song> songs=new ArrayList<Song>();for(int offset=0;offset<names.size();offset+=400){int end=Math.min(names.size(),offset+400);StringBuilder sql=new StringBuilder("SELECT id,title,artist,album,suffix,duration,album_id,track,artist_id,genre,disc,cover_art FROM song WHERE audio_name IN (");String[] args=new String[end-offset];for(int n=offset;n<end;n++){if(n>offset)sql.append(',');sql.append('?');args[n-offset]=names.get(n);}sql.append(')');Cursor c=getReadableDatabase().rawQuery(sql.toString(),args);try{while(c.moveToNext())songs.add(readSong(c));}finally{c.close();}}
        return songs;
    }
    public synchronized Song song(String id) {
        if(id.startsWith("local:")){Cursor local=getReadableDatabase().rawQuery("SELECT id,title,artist,album,suffix,duration,album_id,track,artist_id,genre,disc,cover_art,path FROM local_song WHERE id=?",new String[]{id});try{return local.moveToFirst()?readSong(local,local.getString(12)):null;}finally{local.close();}}
        Cursor c=getReadableDatabase().rawQuery("SELECT id,title,artist,album,suffix,duration,album_id,track,artist_id,genre,disc,cover_art FROM song WHERE id=?",new String[]{id});
        try { return c.moveToFirst() ? readSong(c) : null; }
        finally { c.close(); }
    }
    private static Song readSong(Cursor c) { return new Song(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getString(6),c.getInt(7),c.getString(8),c.getString(9),c.getInt(10),c.getString(11)); }
    private static Song readSong(Cursor c,String path){return new Song(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getString(6),c.getInt(7),c.getString(8),c.getString(9),c.getInt(10),c.getString(11),path);}
    public static final class LocalEntry {
        public final Song song;public final long bytes,modified;
        public LocalEntry(Song song,long bytes,long modified){this.song=song;this.bytes=bytes;this.modified=modified;}
    }
    public synchronized List<LocalEntry> localEntries(){List<LocalEntry> result=new ArrayList<LocalEntry>();Cursor c=getReadableDatabase().rawQuery("SELECT id,title,artist,album,suffix,duration,album_id,track,artist_id,genre,disc,cover_art,path,bytes,modified FROM local_song ORDER BY title COLLATE NOCASE,id",null);try{while(c.moveToNext())result.add(new LocalEntry(readSong(c,c.getString(12)),c.getLong(13),c.getLong(14)));}finally{c.close();}return result;}
    public synchronized List<Song> localSongs(){List<Song> result=new ArrayList<Song>();for(LocalEntry e:localEntries())result.add(e.song);return result;}
    public synchronized int localCount(){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM local_song",null);try{c.moveToFirst();return c.getInt(0);}finally{c.close();}}
    /** An unavailable/partial scan never calls this; committed snapshots include removals. */
    public synchronized void replaceLocalSongs(List<LocalEntry> entries){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("local_song",null,null);for(LocalEntry e:entries){if(!e.song.local() || !e.song.id.startsWith("local:"))throw new IllegalArgumentException("Invalid local entry");ContentValues v=songValues(e.song);v.remove("audio_name");v.put("path",e.song.localPath);v.put("bytes",e.bytes);v.put("modified",e.modified);db.insertOrThrow("local_song",null,v);}db.setTransactionSuccessful();}finally{db.endTransaction();}changed();}
    private static ContentValues songValues(Song s) {
        ContentValues v=new ContentValues();v.put("id",s.id);v.put("title",s.title);v.put("artist",s.artist);v.put("album",s.album);v.put("suffix",s.suffix);v.put("duration",s.duration);v.put("album_id",s.albumId);v.put("track",s.track);v.put("artist_id",s.artistId);v.put("genre",s.genre);v.put("disc",s.disc);v.put("cover_art",s.coverArt);v.put("audio_name",CacheKey.audioName(s.id));return v;
    }
    /** Commit only a complete paged snapshot. Offline audio itself remains untouched. */
    public synchronized void replaceCatalog(Library catalog) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("artist",null,null); db.delete("album",null,null); db.execSQL("UPDATE song SET in_catalog=0");
            for(Library.Entity a:catalog.artists) { ContentValues v=new ContentValues(); v.put("id",a.id); v.put("name",a.name); db.insertOrThrow("artist",null,v); }
            for(Library.Entity a:catalog.albums) { ContentValues v=new ContentValues(); v.put("id",a.id); v.put("name",a.name); v.put("artist_id",a.artistId); v.put("artist",a.artist); db.insertOrThrow("album",null,v); }
            for(Song s:catalog.songs) { ContentValues v=songValues(s); v.put("in_catalog",1); db.insertWithOnConflict("song",null,v,SQLiteDatabase.CONFLICT_REPLACE); }
            ContentValues stamp=new ContentValues(); stamp.put("last_successful_sync",System.currentTimeMillis());stamp.put("catalog_synced",1);db.update("server",stamp,"id=1",null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    changed();}
    public synchronized List<Library.Entity> artists() {
        List<Library.Entity> result=new ArrayList<Library.Entity>(); Cursor c=getReadableDatabase().rawQuery("SELECT id,name FROM artist ORDER BY name COLLATE NOCASE",null);
        try { while(c.moveToNext()) result.add(new Library.Entity(c.getString(0),c.getString(1),"","")); } finally { c.close(); } return result;
    }
    public synchronized List<Library.Entity> albums() {
        List<Library.Entity> result=new ArrayList<Library.Entity>(); Cursor c=getReadableDatabase().rawQuery("SELECT id,name,artist_id,artist FROM album ORDER BY name COLLATE NOCASE",null);
        try { while(c.moveToNext()) result.add(new Library.Entity(c.getString(0),c.getString(1),c.getString(2),c.getString(3))); } finally { c.close(); } return result;
    }
    public synchronized void enqueueDownloads(List<String> ids) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Cursor c=db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM download_queue",null); int next; try { c.moveToFirst(); next=c.getInt(0); } finally {c.close();}
            for(String id:ids) { if(id.startsWith("local:"))continue;ContentValues v=new ContentValues(); v.put("song_id",id); v.put("position",next++); if(db.insertWithOnConflict("download_queue",null,v,SQLiteDatabase.CONFLICT_IGNORE)==-1){v.put("state",0);v.put("received",0);v.put("total",-1);v.put("error","");db.update("download_queue",v,"song_id=? AND state=2",new String[]{id});} }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    changed();}
    public synchronized String nextDownload() {
        Cursor c=getReadableDatabase().rawQuery("SELECT song_id FROM download_queue WHERE state=0 ORDER BY position LIMIT 1",null); try {return c.moveToFirst()?c.getString(0):null;} finally {c.close();}
    }
    public synchronized void completedDownload(String id) {ContentValues v=new ContentValues();v.put("state",2);v.put("updated",System.currentTimeMillis());v.put("error","");getWritableDatabase().update("download_queue",v,"song_id=?",new String[]{id});getWritableDatabase().execSQL("DELETE FROM download_queue WHERE state=2 AND song_id NOT IN (SELECT song_id FROM download_queue WHERE state=2 ORDER BY updated DESC LIMIT 100)");changed();}
    public synchronized void failedDownload(String id) {failedDownload(id,"Download failed");changed();}
    public synchronized void failedDownload(String id,String error) { ContentValues v=new ContentValues(); v.put("state",1);v.put("error",error);v.put("updated",System.currentTimeMillis()); getWritableDatabase().update("download_queue",v,"song_id=?",new String[]{id}); }
    public synchronized void retryDownloads() { ContentValues v=new ContentValues(); v.put("state",0); getWritableDatabase().update("download_queue",v,"state=1",null); changed();}
    public synchronized int pendingDownloads() { Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM download_queue WHERE state<>2",null); try {c.moveToFirst();return c.getInt(0);} finally {c.close();} }
    public synchronized void removeDownload(String id) { getWritableDatabase().delete("download_queue","song_id=?",new String[]{id}); changed();}
    public synchronized void clearDownloads() {getWritableDatabase().delete("download_queue",null,null);changed();}
    public static final class Download {
        public final Song song;public final int state;public final long received,total;public final String error;
        Download(Song song,int state,long received,long total,String error){this.song=song;this.state=state;this.received=received;this.total=total;this.error=error;}
    }
    public synchronized int downloadCount(int state){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM download_queue WHERE state=?",new String[]{Integer.toString(state)});try{c.moveToFirst();return c.getInt(0);}finally{c.close();}}
    public synchronized List<Download> downloads(){return downloads(0,500);}
    public synchronized List<Download> downloads(int offset,int limit){
        List<Download> result=new ArrayList<Download>();Cursor c=getReadableDatabase().rawQuery("SELECT s.id,s.title,s.artist,s.album,s.suffix,s.duration,s.album_id,s.track,s.artist_id,s.genre,s.disc,s.cover_art,q.state,q.received,q.total,q.error FROM download_queue q JOIN song s ON s.id=q.song_id ORDER BY CASE WHEN q.state=2 THEN 1 ELSE 0 END,q.position LIMIT ? OFFSET ?",new String[]{Integer.toString(Math.min(500,Math.max(1,limit))),Integer.toString(Math.max(0,offset))});
        try{while(c.moveToNext())result.add(new Download(readSong(c),c.getInt(12),c.getLong(13),c.getLong(14),c.getString(15)));}finally{c.close();}return result;
    }
    public synchronized void downloadProgress(String id,long received,long total){ContentValues v=new ContentValues();v.put("received",received);v.put("total",total);getWritableDatabase().update("download_queue",v,"song_id=?",new String[]{id});}
    public synchronized void retryDownload(String id){ContentValues v=new ContentValues();v.put("state",0);v.put("error","");getWritableDatabase().update("download_queue",v,"song_id=?",new String[]{id});changed();}
    public static final class AudioState {public final long downloaded,lastPlayed;public final int plays;public final boolean pinned;AudioState(long d,long l,int p,boolean pin){downloaded=d;lastPlayed=l;plays=p;pinned=pin;}}
    public synchronized int pinnedCount(){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM (SELECT song_id FROM audio_state WHERE pinned=1 UNION SELECT ps.song_id FROM playlist_song ps JOIN playlist p ON p.id=ps.playlist_id WHERE p.offline_sync=1)",null);try{c.moveToFirst();return c.getInt(0);}finally{c.close();}}
    private void ensureAudioState(String id){ContentValues v=new ContentValues();v.put("song_id",id);getWritableDatabase().insertWithOnConflict("audio_state",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
    public synchronized AudioState audioState(String id){Cursor c=getReadableDatabase().rawQuery("SELECT downloaded_at,last_played,play_count,pinned FROM audio_state WHERE song_id=?",new String[]{id});try{return c.moveToFirst()?new AudioState(c.getLong(0),c.getLong(1),c.getInt(2),c.getInt(3)!=0):new AudioState(0,0,0,false);}finally{c.close();}}
    public synchronized void downloaded(String id,long when){ensureAudioState(id);ContentValues v=new ContentValues();v.put("downloaded_at",when);getWritableDatabase().update("audio_state",v,"song_id=?",new String[]{id});changed();}
    public synchronized void listened(String id,long when){ensureAudioState(id);getWritableDatabase().execSQL("UPDATE audio_state SET last_played=?,play_count=play_count+1 WHERE song_id=?",new Object[]{when,id});}
    /** Count and enqueue together. Local files never enter the server outbox. */
    public synchronized void recordListening(ListenEvent event,long qualifiedAt){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            if(!event.songId.startsWith("local:")){
                if(event.account==null || !event.account.equals(accountKey()))return;
                ContentValues v=new ContentValues();v.put("session_id",event.sessionId);v.put("account_key",event.account);v.put("song_id",event.songId);v.put("listened_at",event.time);
                if(db.insertWithOnConflict("listen_event",null,v,SQLiteDatabase.CONFLICT_IGNORE)==-1)return;
            }
            listened(event.songId,qualifiedAt);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private String likeAccount(String id){String key=id.startsWith("local:")?"":accountKey();return key==null?"":key;}
    public synchronized boolean isLiked(String id){
        Cursor c=getReadableDatabase().rawQuery("SELECT liked FROM track_like WHERE account_key=? AND song_id=?",new String[]{likeAccount(id),id});
        try{return c.moveToFirst() && c.getInt(0)!=0;}finally{c.close();}
    }
    public synchronized Set<String> likedIds(){
        String key=accountKey();Set<String> result=new HashSet<String>();Cursor c=getReadableDatabase().rawQuery("SELECT song_id FROM track_like WHERE liked=1 AND (account_key=? OR account_key='')",new String[]{key==null?"":key});
        try{while(c.moveToNext())result.add(c.getString(0));}finally{c.close();}return result;
    }
    public synchronized boolean toggleLike(String account,String id){
        boolean local=id.startsWith("local:");
        String owner=local?"":account;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            if(!local && (account==null || !account.equals(accountKey())))throw new IllegalStateException("Account changed");
            boolean liked=!isLiked(id);ContentValues v=new ContentValues();v.put("account_key",owner);v.put("song_id",id);
            db.insertWithOnConflict("track_like",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE track_like SET liked=?,revision=revision+1,dirty=? WHERE account_key=? AND song_id=?",new Object[]{liked?1:0,local?0:1,owner,id});
            db.setTransactionSuccessful();return liked;
        }finally{db.endTransaction();}
    }
    public synchronized List<Song> favoriteSongs(){
        Set<String> liked=likedIds();List<Song> result=new ArrayList<Song>();
        for(Song song:songs())if(liked.contains(song.id))result.add(song);
        for(Song song:localSongs())if(liked.contains(song.id))result.add(song);return result;
    }
    public synchronized int pendingFeedback(String account){
        if(account==null)return 0;
        Cursor c=getReadableDatabase().rawQuery("SELECT (SELECT COUNT(*) FROM track_like WHERE account_key=? AND dirty=1)+(SELECT COUNT(*) FROM listen_event WHERE account_key=?)",new String[]{account,account});
        try{c.moveToFirst();return c.getInt(0);}finally{c.close();}
    }
    public synchronized List<LikeChange> pendingLikes(String account,int limit){
        List<LikeChange> result=new ArrayList<LikeChange>();Cursor c=getReadableDatabase().rawQuery("SELECT song_id,liked,revision FROM track_like WHERE account_key=? AND dirty=1 ORDER BY song_id LIMIT ?",new String[]{account,Integer.toString(limit)});
        try{while(c.moveToNext())result.add(new LikeChange(account,c.getString(0),c.getInt(1)!=0,c.getLong(2)));}finally{c.close();}return result;
    }
    public synchronized void acknowledgeLike(LikeChange change){
        ContentValues v=new ContentValues();v.put("dirty",0);getWritableDatabase().update("track_like",v,"account_key=? AND song_id=? AND revision=?",new String[]{change.account,change.songId,Long.toString(change.revision)});
    }
    public synchronized List<ListenEvent> pendingListens(String account,int limit){
        List<ListenEvent> result=new ArrayList<ListenEvent>();Cursor c=getReadableDatabase().rawQuery("SELECT session_id,song_id,listened_at FROM listen_event WHERE account_key=? ORDER BY listened_at,session_id LIMIT ?",new String[]{account,Integer.toString(limit)});
        try{while(c.moveToNext())result.add(new ListenEvent(c.getString(0),account,c.getString(1),c.getLong(2)));}finally{c.close();}return result;
    }
    public synchronized void acknowledgeListens(List<ListenEvent> events){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            for(ListenEvent event:events)db.delete("listen_event","account_key=? AND session_id=?",new String[]{event.account,event.sessionId});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Only a complete remote snapshot clears clean likes. Offline edits retain priority. */
    public synchronized void applyStarredSnapshot(String account,List<Song> songs){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            if(!account.equals(accountKey()))throw new IllegalStateException("Account changed");
            Set<String> seen=new HashSet<String>();for(Song song:songs)if(song.id.length()==0 || song.id.startsWith("local:") || !seen.add(song.id))throw new IllegalArgumentException("Invalid starred snapshot");
            db.execSQL("UPDATE track_like SET liked=0 WHERE account_key=? AND dirty=0",new Object[]{account});
            for(Song song:songs){
                ContentValues metadata=songValues(song);if(db.update("song",metadata,"id=?",new String[]{song.id})==0)db.insertOrThrow("song",null,metadata);
                ContentValues v=new ContentValues();v.put("account_key",account);v.put("song_id",song.id);v.put("liked",1);
                db.insertWithOnConflict("track_like",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                db.execSQL("UPDATE track_like SET liked=1 WHERE account_key=? AND song_id=? AND dirty=0",new Object[]{account,song.id});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    changed();}
    public synchronized void pin(List<String> ids,boolean pinned){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String id:ids){ensureAudioState(id);ContentValues v=new ContentValues();v.put("pinned",pinned?1:0);db.update("audio_state",v,"song_id=?",new String[]{id});}db.setTransactionSuccessful();}finally{db.endTransaction();}changed();}
    public synchronized Set<String> queuedIds(){Set<String> ids=new HashSet<String>();Cursor c=getReadableDatabase().rawQuery("SELECT song_id FROM download_queue WHERE state<>2",null);try{while(c.moveToNext())ids.add(c.getString(0));}finally{c.close();}return ids;}
    private static ContentValues values(Playlist p) {
        ContentValues v = new ContentValues(); v.put("id", p.id); v.put("name", p.name); v.put("changed", p.changed);
        v.put("song_count", p.songCount); v.put("duration", p.duration); return v;
    }
    public synchronized void replacePlaylists(List<Playlist> playlists) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Set<String> retained = new HashSet<String>();
            for (Playlist p : playlists) {
                if (!retained.add(p.id)) throw new IllegalArgumentException("Duplicate playlist ID.");
                ContentValues v = values(p);
                if (db.update("playlist", v, "id=?", new String[]{p.id}) == 0) db.insertOrThrow("playlist", null, v);
            }
            Cursor c = db.rawQuery("SELECT id FROM playlist", null);
            List<String> removed = new ArrayList<String>();
            try { while(c.moveToNext()) if (!retained.contains(c.getString(0))) removed.add(c.getString(0)); } finally { c.close(); }
            for (String id : removed) { db.delete("playlist_song", "playlist_id=?", new String[]{id}); db.delete("playlist", "id=?", new String[]{id}); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    changed();}
    public synchronized void saveDetail(PlaylistDetail detail) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v = values(detail.playlist); v.put("detail_cached", 1);
            if (db.update("playlist", v, "id=?", new String[]{detail.playlist.id}) == 0) db.insertOrThrow("playlist", null, v);
            db.delete("playlist_song", "playlist_id=?", new String[]{detail.playlist.id}); int position = 0;
            for (Song s : detail.songs) {
                ContentValues song = songValues(s);
                if (db.update("song", song, "id=?", new String[]{s.id}) == 0) db.insertOrThrow("song", null, song);
                ContentValues link = new ContentValues(); link.put("playlist_id",detail.playlist.id); link.put("song_id",s.id); link.put("position",position++);
                db.insertOrThrow("playlist_song", null, link);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    changed();}
    public synchronized PlaylistDetail detail(String id) {
        SQLiteDatabase db = getReadableDatabase(); Playlist playlist;
        Cursor c = db.rawQuery("SELECT id,name,changed,song_count,duration,detail_cached FROM playlist WHERE id=?", new String[]{id});
        try {
            if (!c.moveToFirst() || c.getInt(5) == 0) return null;
            playlist = new Playlist(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getLong(4));
        } finally { c.close(); }
        List<Song> songs = new ArrayList<Song>();
        c = db.rawQuery("SELECT s.id,s.title,s.artist,s.album,s.suffix,s.duration,s.album_id,s.track,s.artist_id,s.genre,s.disc,s.cover_art FROM song s JOIN playlist_song p ON s.id=p.song_id WHERE p.playlist_id=? ORDER BY p.position", new String[]{id});
        try { while(c.moveToNext()) songs.add(readSong(c)); }
        finally { c.close(); }
        return new PlaylistDetail(playlist, songs);
    }
}
