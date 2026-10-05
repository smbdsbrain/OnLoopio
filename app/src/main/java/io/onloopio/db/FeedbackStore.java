package io.onloopio.db;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import io.onloopio.model.*;
import java.io.IOException;
import java.util.*;
import io.onloopio.sync.FeedbackRetry;
public final class FeedbackStore {
    private final MetadataStore store;
    public FeedbackStore(MetadataStore s){store=s;}
    static void schema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE listen_history(attempt_id TEXT PRIMARY KEY,account TEXT NOT NULL,song_id TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,started INTEGER NOT NULL,played INTEGER NOT NULL DEFAULT 0,qualified INTEGER NOT NULL DEFAULT 0,outcome TEXT NOT NULL DEFAULT 'playing')");
        db.execSQL("CREATE INDEX history_started ON listen_history(started)");
        db.execSQL("CREATE TABLE feedback_retry(account TEXT NOT NULL,kind TEXT NOT NULL,item TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'pending',PRIMARY KEY(account,kind,item))");
    }
    static void clocks(SQLiteDatabase db){db.execSQL("ALTER TABLE feedback_retry ADD COLUMN attempted_at INTEGER NOT NULL DEFAULT 0");}
    public void failure(String account,String kind,String item,long revision,IOException error,long now){SQLiteDatabase db=store.getWritableDatabase();db.execSQL("INSERT OR IGNORE INTO feedback_retry(account,kind,item,revision) VALUES(?,?,?,?)",new Object[]{account,kind,item,revision});db.execSQL("UPDATE feedback_retry SET attempts=attempts+1,revision=?,state=?,attempted_at=? WHERE account=? AND kind=? AND item=?",new Object[]{revision,FeedbackRetry.kind(error),now,account,kind,item});long attempts=android.database.DatabaseUtils.longForQuery(db,"SELECT attempts FROM feedback_retry WHERE account=? AND kind=? AND item=?",new String[]{account,kind,item});db.execSQL("UPDATE feedback_retry SET next_at=? WHERE account=? AND kind=? AND item=?",new Object[]{now+FeedbackRetry.delay((int)attempts),account,kind,item});}
    public void acknowledge(String account,String kind,String item){store.getWritableDatabase().delete("feedback_retry","account=? AND kind=? AND item=?",new String[]{account,kind,item});}
    public void retry(String account){store.getWritableDatabase().execSQL("UPDATE feedback_retry SET next_at=0,state='pending' WHERE account=?",new Object[]{account});}
    public void deleteHeld(String account,String kind,String item){SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{if(kind.equals("listen"))db.delete("listen_event","account_key=? AND session_id=?",new String[]{account,item});else db.execSQL("UPDATE track_like SET dirty=0 WHERE account_key=? AND song_id=?",new Object[]{account,item});acknowledge(account,kind,item);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public static final class Held {public final String kind,item,state;public final long revision;Held(String k,String i,String s,long r){kind=k;item=i;state=s;revision=r;}}
    public List<Held> held(String account){List<Held> rows=new ArrayList<Held>();Cursor c=store.getReadableDatabase().rawQuery("SELECT kind,item,state,revision FROM feedback_retry WHERE account=? ORDER BY next_at LIMIT 100",new String[]{account==null?"":account});try{while(c.moveToNext())rows.add(new Held(c.getString(0),c.getString(1),c.getString(2),c.getLong(3)));}finally{c.close();}return rows;}
    public void retry(String account,Held item){store.getWritableDatabase().execSQL("UPDATE feedback_retry SET next_at=0,state='pending' WHERE account=? AND kind=? AND item=? AND revision=?",new Object[]{account,item.kind,item.item,item.revision});}
    public void deleteHeld(String account,Held item){SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{if(item.kind.equals("listen"))db.delete("listen_event","account_key=? AND session_id=?",new String[]{account,item.item});else db.execSQL("UPDATE track_like SET dirty=0 WHERE account_key=? AND song_id=? AND revision=?",new Object[]{account,item.item,item.revision});db.delete("feedback_retry","account=? AND kind=? AND item=? AND revision=?",new String[]{account,item.kind,item.item,Long.toString(item.revision)});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public void prune(long now){SQLiteDatabase db=store.getWritableDatabase();db.execSQL("DELETE FROM listen_history WHERE (started<? OR attempt_id NOT IN (SELECT attempt_id FROM listen_history ORDER BY started DESC LIMIT 10000)) AND attempt_id NOT IN (SELECT session_id FROM listen_event) AND attempt_id NOT IN (SELECT attempt_id FROM playback_session)",new Object[]{now-90L*86400000});}
    public static final class Row{public final String id,title,artist,outcome;public final long started,played;public final boolean clockUncertain;Row(String i,String t,String a,String o,long s,long p,boolean u){id=i;title=t;artist=a;outcome=o;started=s;played=p;clockUncertain=u;}}
    public List<Row> history(String account,int offset){List<Row> rows=new ArrayList<Row>();Cursor c=store.getReadableDatabase().rawQuery("SELECT attempt_id,title,artist,outcome,started,played,clock_uncertain FROM listen_history WHERE account=? OR account='' ORDER BY started DESC LIMIT 100 OFFSET ?",new String[]{account==null?"":account,Integer.toString(Math.max(0,offset))});try{while(c.moveToNext())rows.add(new Row(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getInt(6)!=0));}finally{c.close();}return rows;}
}
