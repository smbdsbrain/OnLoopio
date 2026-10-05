package io.onloopio.library;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Private ownership registry, independent of catalog/account resets. */
public final class AudioFileIndex extends SQLiteOpenHelper {
    public static final Object IO=new Object();
    public static final class Entry {
        public final String account,token,path;public final long bytes,modified;
        Entry(String a,String t,String p,long b,long m){account=a;token=t;path=p;bytes=b;modified=m;}
        // VFAT caches an odd-second mtime until remount; disk timestamps have 2-second resolution.
        public boolean matches(File file){return file.isFile() && file.length()>=16 && file.length()==bytes && file.lastModified()/2000==modified/2000;}
    }
    public AudioFileIndex(Context context){super(context.getApplicationContext()==null?context:context.getApplicationContext(),"audio-files.db",null,2);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE audio_file(account TEXT NOT NULL,token TEXT NOT NULL,path TEXT NOT NULL COLLATE NOCASE UNIQUE,bytes INTEGER NOT NULL,modified INTEGER NOT NULL,PRIMARY KEY(account,token))");publicationSchema(db);}
    private void publicationSchema(SQLiteDatabase db){db.execSQL("CREATE TABLE audio_publication(account TEXT NOT NULL,token TEXT NOT NULL,partial TEXT NOT NULL,path TEXT NOT NULL,bytes INTEGER NOT NULL,modified INTEGER NOT NULL,PRIMARY KEY(account,token))");}
    public void onUpgrade(SQLiteDatabase db,int from,int to){if(from==1 && to==2)publicationSchema(db);else throw new IllegalStateException("No audio registry migration");}
    public void beginPublication(String account,String token,File partial,File target)throws java.io.IOException{ContentValues v=new ContentValues();v.put("account",account);v.put("token",token);v.put("partial",partial.getCanonicalPath());v.put("path",target.getCanonicalPath());v.put("bytes",partial.length());v.put("modified",partial.lastModified());getWritableDatabase().insertWithOnConflict("audio_publication",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public void finishPublication(String account,String token){getWritableDatabase().delete("audio_publication","account=? AND token=?",new String[]{account,token});}
    public void recover(String account,File root,File staging)throws java.io.IOException{
        Cursor c=getReadableDatabase().rawQuery("SELECT token,partial,path,bytes,modified FROM audio_publication WHERE account=?",new String[]{account});try{while(c.moveToNext()){
            File part=new File(c.getString(1)),target=new File(c.getString(2));String allowed=staging.getCanonicalPath()+File.separator,base=root.getCanonicalPath()+File.separator;if(!part.getCanonicalPath().startsWith(allowed) || !target.getCanonicalPath().startsWith(base))throw new java.io.IOException("Invalid publication path");MusicPaths.resolve(root,target.getCanonicalPath().substring(base.length()));
            Entry expected=new Entry(account,c.getString(0),target.getPath(),c.getLong(3),c.getLong(4));
            if(expected.matches(target)){record(account,expected.token,target.getCanonicalPath(),target.length(),target.lastModified());finishPublication(account,expected.token);}
            else if(expected.matches(part) && !target.exists()){if(part.renameTo(target)){record(account,expected.token,target.getCanonicalPath(),target.length(),target.lastModified());finishPublication(account,expected.token);}}
            else if(!part.exists())finishPublication(account,expected.token);
        }}finally{c.close();}
    }
    public List<Entry> entries(String account){
        Cursor c=getReadableDatabase().rawQuery("SELECT account,token,path,bytes,modified FROM audio_file"+(account==null?"":" WHERE account=?"),account==null?null:new String[]{account});List<Entry> result=new ArrayList<Entry>();
        try{while(c.moveToNext())result.add(new Entry(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4)));}finally{c.close();}return result;
    }
    public Entry find(String account,String token){return one("account=? AND token=?",new String[]{account,token});}
    public Entry atPath(String account,String path){return one("account=? AND path=?",new String[]{account,path});}
    private Entry one(String where,String[] args){Cursor c=getReadableDatabase().rawQuery("SELECT account,token,path,bytes,modified FROM audio_file WHERE "+where+" LIMIT 1",args);try{return c.moveToFirst()?new Entry(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4)):null;}finally{c.close();}}
    public void record(String account,String token,String path,long bytes,long modified){
        ContentValues v=new ContentValues();v.put("account",account);v.put("token",token);v.put("path",path);v.put("bytes",bytes);v.put("modified",modified);
        SQLiteDatabase db=getWritableDatabase();if(db.update("audio_file",v,"account=? AND token=?",new String[]{account,token})==0)db.insertOrThrow("audio_file",null,v);
    }
    public void forget(String account,String token){getWritableDatabase().delete("audio_file","account=? AND token=?",new String[]{account,token});}
    public Set<String> ownedPaths(){Set<String> result=new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);for(Entry e:entries(null))if(e.matches(new File(e.path)))result.add(e.path);return result;}
    public boolean reserved(String path){Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM audio_file WHERE path=? COLLATE NOCASE",new String[]{path});try{return c.moveToFirst();}finally{c.close();}}
}
