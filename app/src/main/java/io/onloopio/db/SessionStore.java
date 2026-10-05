package io.onloopio.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import io.onloopio.player.PlaybackQueue;
import io.onloopio.player.ListeningSession;
import io.onloopio.model.Song;
import java.io.*;
import java.util.*;

/** Private SQLite checkpoint. Queue rows change only on edits; progress updates one small row. */
public final class SessionStore {
    private final MetadataStore store;
    public SessionStore(MetadataStore store){this.store=store;}
    static void schema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE playback_session(id INTEGER PRIMARY KEY CHECK(id=1),session_id TEXT NOT NULL,traversal BLOB NOT NULL,position INTEGER NOT NULL DEFAULT 0,attempt_id TEXT NOT NULL,started INTEGER NOT NULL DEFAULT 0,played INTEGER NOT NULL DEFAULT 0,qualified INTEGER NOT NULL DEFAULT 0,policy INTEGER NOT NULL DEFAULT 2)");
        db.execSQL("CREATE TABLE queue_entry(entry_id TEXT PRIMARY KEY,position INTEGER NOT NULL,account TEXT NOT NULL,song_id TEXT NOT NULL,metadata BLOB NOT NULL)");
        db.execSQL("CREATE TABLE attempt_ledger(attempt_id TEXT PRIMARY KEY,account TEXT NOT NULL,song_id TEXT NOT NULL,started INTEGER NOT NULL,played INTEGER NOT NULL DEFAULT 0,policy INTEGER NOT NULL,qualified_at INTEGER NOT NULL DEFAULT 0,outcome TEXT NOT NULL DEFAULT 'playing')");
        // Retain deduplication for legacy pending events; old play counts are never invented as history.
        db.execSQL("INSERT INTO attempt_ledger(attempt_id,account,song_id,started,policy,qualified_at) SELECT session_id,account_key,song_id,listened_at,1,listened_at FROM listen_event");
    }
    public static final class Restored {
        public final PlaybackQueue queue; public final String attempt; public final int position,policy;public final long started,played,clockWall,clockElapsed;public final boolean qualified,clockUncertain;
        Restored(PlaybackQueue q,String a,int p,long s,long l,boolean b,int v,long wall,long elapsed,boolean uncertain){queue=q;attempt=a;position=p;started=s;played=l;qualified=b;policy=v;clockWall=wall;clockElapsed=elapsed;clockUncertain=uncertain;}
    }
    static void clocks(SQLiteDatabase db){
        for(String table:new String[]{"playback_session","attempt_ledger","listen_history"})db.execSQL("ALTER TABLE "+table+" ADD COLUMN clock_uncertain INTEGER NOT NULL DEFAULT 1");
        db.execSQL("ALTER TABLE playback_session ADD COLUMN clock_wall INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE playback_session ADD COLUMN clock_elapsed INTEGER NOT NULL DEFAULT 0");
    }
    public void save(PlaybackQueue queue,String attempt,long started,ListeningSession listening,int position,boolean structure){
        save(queue,attempt,started,listening,position,structure,structure);
    }
    public void save(PlaybackQueue queue,String attempt,long started,ListeningSession listening,int position,boolean structure,boolean entriesChanged){
        if(attempt==null)return;SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            if(entriesChanged){db.delete("queue_entry",null,null);int n=0;for(PlaybackQueue.Entry e:queue.entries()){ContentValues v=new ContentValues();v.put("entry_id",e.id);v.put("position",n++);v.put("account",e.account);v.put("song_id",e.song.id);v.put("metadata",encodeSong(e.song));db.insertOrThrow("queue_entry",null,v);}}
            boolean qualified=android.database.DatabaseUtils.longForQuery(db,"SELECT EXISTS(SELECT 1 FROM attempt_ledger WHERE attempt_id=? AND qualified_at>0)",new String[]{attempt})!=0;
            ContentValues v=new ContentValues();v.put("id",1);v.put("session_id",queue.sessionId);if(structure)v.put("traversal",encodeTraversal(queue));v.put("position",Math.max(0,position));v.put("attempt_id",attempt);v.put("started",started);v.put("played",listening.playedMillis());v.put("qualified",qualified?1:0);v.put("policy",listening.policyVersion());
            int uncertain=listening.clockUncertain()?1:0;v.put("clock_uncertain",uncertain);v.put("clock_wall",listening.clockWall());v.put("clock_elapsed",listening.clockElapsed());
            if(db.update("playback_session",v,"id=1",null)==0){v.put("traversal",encodeTraversal(queue));db.insertOrThrow("playback_session",null,v);}
            PlaybackQueue.Entry current=queue.current();if(current!=null && started>0){ContentValues a=new ContentValues();a.put("attempt_id",attempt);a.put("account",current.account);a.put("song_id",current.song.id);a.put("started",started);a.put("policy",listening.policyVersion());a.put("clock_uncertain",uncertain);db.insertWithOnConflict("attempt_ledger",null,a,SQLiteDatabase.CONFLICT_IGNORE);db.execSQL("UPDATE attempt_ledger SET played=MAX(played,?),clock_uncertain=MAX(clock_uncertain,?) WHERE attempt_id=?",new Object[]{listening.playedMillis(),uncertain,attempt});}
            if(current!=null && started>0){db.execSQL("INSERT OR IGNORE INTO listen_history(attempt_id,account,song_id,title,artist,started,clock_uncertain) VALUES(?,?,?,?,?,?,?)",new Object[]{attempt,current.account,current.song.id,current.song.title,current.song.artist,started,uncertain});if(android.database.DatabaseUtils.longForQuery(db,"SELECT changes()",null)!=0)new FeedbackStore(store).prune(System.currentTimeMillis());db.execSQL("UPDATE listen_history SET played=MAX(played,?),qualified=MAX(qualified,?),clock_uncertain=MAX(clock_uncertain,?) WHERE attempt_id=?",new Object[]{listening.playedMillis(),qualified?1:0,uncertain,attempt});}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public Restored load(){
        SQLiteDatabase db=store.getReadableDatabase();Cursor c=db.rawQuery("SELECT session_id,traversal,position,attempt_id,started,played,qualified,policy,clock_wall,clock_elapsed,clock_uncertain FROM playback_session WHERE id=1",null);
        try{if(!c.moveToFirst())return null;PlaybackQueue q=new PlaybackQueue(c.getString(0),new Random());List<PlaybackQueue.Entry> entries=new ArrayList<PlaybackQueue.Entry>();Cursor e=db.rawQuery("SELECT entry_id,account,metadata FROM queue_entry ORDER BY position LIMIT 10001",null);
            try{while(e.moveToNext())entries.add(new PlaybackQueue.Entry(e.getString(0),e.getString(1),decodeSong(e.getBlob(2))));}finally{e.close();}
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(c.getBlob(1)));List<String> h=readIds(in),p=readIds(in);q.restore(entries,h,p,in.readInt(),in.readBoolean(),in.readInt());if(in.available()>=4)q.restoreForced(in.readInt());
            boolean qualified=c.getInt(6)!=0,uncertain=c.getInt(10)!=0;Cursor a=db.rawQuery("SELECT qualified_at,clock_uncertain FROM attempt_ledger WHERE attempt_id=?",new String[]{c.getString(3)});try{if(a.moveToFirst()){if(a.getLong(0)>0)qualified=true;uncertain|=a.getInt(1)!=0;}}finally{a.close();}
            return new Restored(q,c.getString(3),c.getInt(2),c.getLong(4),c.getLong(5),qualified,c.getInt(7),c.getLong(8),c.getLong(9),uncertain);
        }catch(IOException invalid){throw new IllegalStateException("Invalid session",invalid);}finally{c.close();}
    }
    public void finish(String attempt,String outcome){if(attempt!=null){ContentValues v=new ContentValues();v.put("outcome",outcome);store.getWritableDatabase().update("attempt_ledger",v,"attempt_id=?",new String[]{attempt});store.getWritableDatabase().update("listen_history",v,"attempt_id=?",new String[]{attempt});}}
    public void clear(){SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{db.delete("playback_session",null,null);db.delete("queue_entry",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    private static byte[] encodeTraversal(PlaybackQueue q){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);writeIds(out,q.history());writeIds(out,q.pending());out.writeInt(q.cursor());out.writeBoolean(q.shuffle());out.writeInt(q.repeat());out.writeInt(q.forcedCount());return bytes.toByteArray();}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private static void writeIds(DataOutputStream out,List<String> ids)throws IOException{out.writeInt(ids.size());for(String id:ids)out.writeUTF(id);}
    private static List<String> readIds(DataInputStream in)throws IOException{int size=in.readInt();if(size<0 || size>PlaybackQueue.LIMIT)throw new IOException("Oversize traversal");List<String> ids=new ArrayList<String>();for(int n=0;n<size;n++)ids.add(in.readUTF());return ids;}
    public static byte[] encodeSong(Song s){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);for(String value:new String[]{s.id,s.title,s.artist,s.album,s.suffix,s.albumId,s.artistId,s.genre,s.coverArt,s.localPath})out.writeUTF(value);out.writeInt(s.duration);out.writeInt(s.track);out.writeInt(s.disc);if(bytes.size()>65536)throw new IOException("Metadata limit");return bytes.toByteArray();}catch(IOException invalid){throw new IllegalArgumentException("Oversize metadata",invalid);}}
    public static Song decodeSong(byte[] bytes)throws IOException{if(bytes.length>65536)throw new IOException("Oversize metadata");DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));String[] s=new String[10];for(int n=0;n<10;n++)s[n]=in.readUTF();return new Song(s[0],s[1],s[2],s[3],s[4],in.readInt(),s[5],in.readInt(),s[6],s[7],in.readInt(),s[8],s[9]);}
}
