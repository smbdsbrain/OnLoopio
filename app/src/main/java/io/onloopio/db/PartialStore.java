package io.onloopio.db;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import io.onloopio.api.ResumableDownload;
public final class PartialStore {
    private final MetadataStore store;
    public PartialStore(MetadataStore store){this.store=store;}
    static void schema(SQLiteDatabase db){db.execSQL("CREATE TABLE audio_partial(account TEXT NOT NULL,token TEXT NOT NULL,validator TEXT NOT NULL DEFAULT '',offset INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT -1,PRIMARY KEY(account,token))");}
    static void aging(SQLiteDatabase db){db.execSQL("ALTER TABLE audio_partial ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");}
    public ResumableDownload.State load(String account,String token){ResumableDownload.State s=new ResumableDownload.State();Cursor c=store.getReadableDatabase().rawQuery("SELECT validator,offset,total FROM audio_partial WHERE account=? AND token=?",new String[]{account,token});try{if(c.moveToFirst()){s.validator=c.getString(0);s.offset=c.getLong(1);s.total=c.getLong(2);}}finally{c.close();}return s;}
    public void save(String account,String token,ResumableDownload.State s){store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO audio_partial(account,token,validator,offset,total,updated_at) VALUES(?,?,?,?,?,?)",new Object[]{account,token,s.validator,s.offset,s.total,System.currentTimeMillis()});}
    public long updated(String account,String token){Cursor c=store.getReadableDatabase().rawQuery("SELECT updated_at FROM audio_partial WHERE account=? AND token=?",new String[]{account,token});try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}}
    public java.util.List<String> tokens(String account){java.util.List<String> result=new java.util.ArrayList<String>();Cursor c=store.getReadableDatabase().rawQuery("SELECT token FROM audio_partial WHERE account=?",new String[]{account});try{while(c.moveToNext())result.add(c.getString(0));}finally{c.close();}return result;}
    public void clear(String account,String token){store.getWritableDatabase().delete("audio_partial","account=? AND token=?",new String[]{account,token});}
}
