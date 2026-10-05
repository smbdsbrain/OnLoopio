package io.onloopio.db;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import io.onloopio.model.*;
import java.io.IOException;
import java.util.*;

/** Immutable membership snapshots. Only a fully reconciled current staging version can activate. */
public final class GenerationStore {
    private final MetadataStore store;
    public GenerationStore(MetadataStore store){this.store=store;}
    static void schema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE offline_generation(id TEXT PRIMARY KEY,account TEXT NOT NULL,playlist_id TEXT NOT NULL,signature TEXT NOT NULL,name TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'preparing',activated INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE offline_member(generation TEXT NOT NULL,position INTEGER NOT NULL,song_id TEXT NOT NULL,token TEXT NOT NULL,metadata BLOB NOT NULL,ready INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(generation,position))");
        db.execSQL("CREATE INDEX offline_member_song ON offline_member(song_id,generation)");
        db.execSQL("CREATE TABLE offline_pointer(account TEXT NOT NULL,playlist_id TEXT NOT NULL,active TEXT,staging TEXT,detached INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account,playlist_id))");
    }
    static void readinessIndex(SQLiteDatabase db){db.execSQL("CREATE INDEX offline_member_ready ON offline_member(generation,token,ready)");}
    static void summaries(SQLiteDatabase db){
        db.execSQL("ALTER TABLE offline_member ADD COLUMN duration INTEGER NOT NULL DEFAULT 0");
        for(String column:new String[]{"member_count","ready_count","unique_ready","seconds_ready","unknown_ready"})db.execSQL("ALTER TABLE offline_generation ADD COLUMN "+column+" INTEGER NOT NULL DEFAULT 0");
        Cursor members=db.rawQuery("SELECT generation,position,metadata FROM offline_member",null);
        try{while(members.moveToNext())db.execSQL("UPDATE offline_member SET duration=? WHERE generation=? AND position=?",new Object[]{SessionStore.decodeSong(members.getBlob(2)).duration,members.getString(0),members.getInt(1)});}catch(IOException invalid){throw new IllegalStateException("Invalid generation metadata",invalid);}finally{members.close();}
        db.execSQL("UPDATE offline_generation SET member_count=(SELECT COUNT(*) FROM offline_member WHERE generation=id),ready_count=(SELECT COUNT(*) FROM offline_member WHERE generation=id AND ready=1),unique_ready=(SELECT COUNT(DISTINCT token) FROM offline_member WHERE generation=id AND ready=1),seconds_ready=COALESCE((SELECT SUM(MAX(duration,0)) FROM offline_member WHERE generation=id AND ready=1),0),unknown_ready=(SELECT COUNT(*) FROM offline_member WHERE generation=id AND ready=1 AND duration<=0)");
        String others="NOT EXISTS(SELECT 1 FROM offline_member WHERE generation=NEW.generation AND token=NEW.token AND position!=NEW.position AND ready=1)";
        db.execSQL("CREATE TRIGGER offline_insert AFTER INSERT ON offline_member BEGIN UPDATE offline_generation SET member_count=member_count+1,ready_count=ready_count+NEW.ready,unique_ready=unique_ready+CASE WHEN NEW.ready=1 AND "+others+" THEN 1 ELSE 0 END,seconds_ready=seconds_ready+NEW.ready*MAX(NEW.duration,0),unknown_ready=unknown_ready+CASE WHEN NEW.ready=1 AND NEW.duration<=0 THEN 1 ELSE 0 END WHERE id=NEW.generation; END");
        db.execSQL("CREATE TRIGGER offline_ready AFTER UPDATE OF ready ON offline_member WHEN OLD.ready!=NEW.ready BEGIN UPDATE offline_generation SET ready_count=ready_count+NEW.ready-OLD.ready,unique_ready=unique_ready+CASE WHEN "+others+" THEN NEW.ready-OLD.ready ELSE 0 END,seconds_ready=seconds_ready+(NEW.ready-OLD.ready)*MAX(NEW.duration,0),unknown_ready=unknown_ready+CASE WHEN NEW.duration<=0 THEN NEW.ready-OLD.ready ELSE 0 END WHERE id=NEW.generation; END");
        String remaining="NOT EXISTS(SELECT 1 FROM offline_member WHERE generation=OLD.generation AND token=OLD.token AND ready=1)";
        db.execSQL("CREATE TRIGGER offline_delete AFTER DELETE ON offline_member BEGIN UPDATE offline_generation SET member_count=member_count-1,ready_count=ready_count-OLD.ready,unique_ready=unique_ready-CASE WHEN OLD.ready=1 AND "+remaining+" THEN 1 ELSE 0 END,seconds_ready=seconds_ready-OLD.ready*MAX(OLD.duration,0),unknown_ready=unknown_ready-CASE WHEN OLD.ready=1 AND OLD.duration<=0 THEN 1 ELSE 0 END WHERE id=OLD.generation; END");
    }
    public String stage(String account,PlaylistDetail detail){
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            String signature=signature(detail);String old=pointer(account,detail.playlist.id,"staging");String active=pointer(account,detail.playlist.id,"active");
            if(matches(old,signature)){db.setTransactionSuccessful();return old;}if(matches(active,signature)){cancel(account,detail.playlist.id);db.setTransactionSuccessful();return active;}
            cancel(account,detail.playlist.id);String id=UUID.randomUUID().toString();ContentValues g=new ContentValues();g.put("id",id);g.put("account",account);g.put("playlist_id",detail.playlist.id);g.put("signature",signature);g.put("name",detail.playlist.name);db.insertOrThrow("offline_generation",null,g);
            int n=0;for(Song s:detail.songs){ContentValues m=new ContentValues();m.put("generation",id);m.put("position",n++);m.put("song_id",s.id);m.put("token",io.onloopio.player.AudioProfile.token(s.id,store.offlineProfile()));m.put("metadata",SessionStore.encodeSong(s));m.put("duration",s.duration);db.insertOrThrow("offline_member",null,m);}
            ContentValues p=new ContentValues();p.put("account",account);p.put("playlist_id",detail.playlist.id);db.insertWithOnConflict("offline_pointer",null,p,SQLiteDatabase.CONFLICT_IGNORE);p.put("staging",id);p.put("detached",0);db.update("offline_pointer",p,"account=? AND playlist_id=?",new String[]{account,detail.playlist.id});
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    private String signature(PlaylistDetail d){StringBuilder b=new StringBuilder(d.playlist.changed+"|"+io.onloopio.player.AudioProfile.specification(store.offlineProfile()));for(Song s:d.songs)b.append('\n').append(s.id).append(':').append(s.duration).append(':').append(s.suffix);return CacheKey.hash(b.toString());}
    private boolean matches(String id,String signature){if(id==null)return false;Cursor c=store.getReadableDatabase().rawQuery("SELECT signature FROM offline_generation WHERE id=?",new String[]{id});try{return c.moveToFirst() && signature.equals(c.getString(0));}finally{c.close();}}
    private String pointer(String account,String playlist,String column){Cursor c=store.getReadableDatabase().rawQuery("SELECT "+column+" FROM offline_pointer WHERE account=? AND playlist_id=?",new String[]{account,playlist});try{return c.moveToFirst()?c.getString(0):null;}finally{c.close();}}
    public void cancel(String account,String playlist){SQLiteDatabase db=store.getWritableDatabase();String id=pointer(account,playlist,"staging");if(id==null)return;db.beginTransaction();try{db.execSQL("UPDATE offline_pointer SET staging=NULL WHERE account=? AND playlist_id=?",new Object[]{account,playlist});db.delete("offline_member","generation=?",new String[]{id});db.delete("offline_generation","id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public void detached(String account,String playlist){store.getWritableDatabase().execSQL("UPDATE offline_pointer SET detached=1 WHERE account=? AND playlist_id=?",new Object[]{account,playlist});store.getWritableDatabase().execSQL("UPDATE playlist SET offline_sync=0 WHERE id=?",new Object[]{playlist});cancel(account,playlist);}
    public void release(String account,String playlist){SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{cancel(account,playlist);String active=pointer(account,playlist,"active");if(active!=null){db.delete("offline_member","generation=?",new String[]{active});db.delete("offline_generation","id=?",new String[]{active});}db.delete("offline_pointer","account=? AND playlist_id=?",new String[]{account,playlist});store.followPlaylist(playlist,false);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    /** Completed names come from the ownership registry + real file validation, never download rows. */
    public void reconcile(String account,List<String> completed,long now){
        Set<String> ready=new HashSet<String>(completed);SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            Cursor c=db.rawQuery("SELECT m.generation,m.position,m.token FROM offline_member m JOIN offline_generation g ON g.id=m.generation WHERE g.account=?",new String[]{account});
            try{while(c.moveToNext())db.execSQL("UPDATE offline_member SET ready=? WHERE generation=? AND position=? AND ready!=?",new Object[]{ready.contains(c.getString(2))?1:0,c.getString(0),c.getInt(1),ready.contains(c.getString(2))?1:0});}finally{c.close();}
            Cursor pointers=db.rawQuery("SELECT playlist_id,active,staging FROM offline_pointer WHERE account=?",new String[]{account});try{while(pointers.moveToNext()){
                String active=pointers.getString(1),stage=pointers.getString(2);if(active!=null)db.execSQL("UPDATE offline_generation SET state=? WHERE id=?",new Object[]{complete(active)?"ready":"degraded",active});
                if(stage!=null && complete(stage)){
                    db.execSQL("UPDATE offline_pointer SET active=?,staging=NULL WHERE account=? AND playlist_id=? AND staging=?",new Object[]{stage,account,pointers.getString(0),stage});db.execSQL("UPDATE offline_generation SET state='ready',activated=? WHERE id=?",new Object[]{now,stage});
                    if(active!=null){db.delete("offline_member","generation=?",new String[]{active});db.delete("offline_generation","id=?",new String[]{active});}
                }
            }}finally{pointers.close();}db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private boolean complete(String id){return android.database.DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM offline_member WHERE generation=? AND ready=0",new String[]{id})==0;}
    public PlaylistDetail active(String account,String playlist){String id=pointer(account,playlist,"active");if(id==null)return null;List<Song> songs=new ArrayList<Song>();Cursor c=store.getReadableDatabase().rawQuery("SELECT metadata FROM offline_member WHERE generation=? ORDER BY position",new String[]{id});try{while(c.moveToNext())songs.add(SessionStore.decodeSong(c.getBlob(0)));}catch(IOException invalid){throw new IllegalStateException(invalid);}finally{c.close();}return new PlaylistDetail(new Playlist(playlist,name(id),id,songs.size(),0),songs);}
    private String name(String id){return android.database.DatabaseUtils.stringForQuery(store.getReadableDatabase(),"SELECT name FROM offline_generation WHERE id=?",new String[]{id});}
    public static final class Summary{public int ready,required,unique,stagingReady,stagingRequired;public boolean active,staging;public long seconds,activated;public boolean knownDuration=true,degraded,detached;}
    public Summary summary(String account,String playlist){Summary result=new Summary();String active=pointer(account,playlist,"active"),stage=pointer(account,playlist,"staging");result.active=active!=null;result.staging=stage!=null;readSummary(active,result,false);readSummary(stage,result,true);Cursor c=store.getReadableDatabase().rawQuery("SELECT detached FROM offline_pointer WHERE account=? AND playlist_id=?",new String[]{account,playlist});try{result.detached=c.moveToFirst() && c.getInt(0)!=0;}finally{c.close();}return result;}
    private void readSummary(String id,Summary r,boolean staging){if(id==null)return;Cursor c=store.getReadableDatabase().rawQuery("SELECT member_count,ready_count,unique_ready,seconds_ready,unknown_ready,activated FROM offline_generation WHERE id=?",new String[]{id});try{if(c.moveToFirst()){if(staging){r.stagingRequired=c.getInt(0);r.stagingReady=c.getInt(1);}else{r.required=c.getInt(0);r.ready=c.getInt(1);r.unique=c.getInt(2);r.seconds=c.getLong(3);r.knownDuration=c.getInt(4)==0;r.activated=c.getLong(5);r.degraded=r.ready!=r.required;}}}finally{c.close();}}
}
