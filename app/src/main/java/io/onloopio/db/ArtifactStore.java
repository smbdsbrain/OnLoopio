package io.onloopio.db;
import android.database.sqlite.SQLiteDatabase;
import io.onloopio.player.AudioProfile;
import io.onloopio.model.Song;
import java.util.List;
public final class ArtifactStore {
    private final MetadataStore store;
    public ArtifactStore(MetadataStore s){store=s;}
    static void schema(SQLiteDatabase db){db.execSQL("CREATE TABLE audio_artifact(account TEXT NOT NULL,token TEXT NOT NULL,song_id TEXT NOT NULL,profile INTEGER NOT NULL,actual TEXT NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(account,token))");db.execSQL("CREATE INDEX audio_artifact_song ON audio_artifact(account,song_id)");}
    static void measurements(SQLiteDatabase db){db.execSQL("ALTER TABLE audio_artifact ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE audio_artifact ADD COLUMN bitrate INTEGER NOT NULL DEFAULT 0");}
    public void record(String account,Song song,int profile,java.io.File file)throws java.io.IOException{io.onloopio.player.AudioFacts facts=io.onloopio.player.AudioFacts.read(file);record(account,song,profile,facts.format,file.length());store.getWritableDatabase().execSQL("UPDATE audio_artifact SET duration_ms=?,bitrate=? WHERE account=? AND token=?",new Object[]{facts.durationMs,facts.bitrate,account,AudioProfile.token(song.id,profile)});}
    public void record(String account,Song song,int profile,String actual,long bytes){store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO audio_artifact(account,token,song_id,profile,actual,bytes) VALUES(?,?,?,?,?,?)",new Object[]{account,AudioProfile.token(song.id,profile),song.id,profile,actual,bytes});}
    public static final class Row{public final String token,actual;public final long bytes,durationMs,bitrate;public final int profile;Row(String t,String a,long b,int p,long d,long r){token=t;actual=a;bytes=b;profile=p;durationMs=d;bitrate=r;}}
    public java.util.List<Row> rows(String account,String song){java.util.List<Row> result=new java.util.ArrayList<Row>();android.database.Cursor cursor=store.getReadableDatabase().rawQuery("SELECT token,actual,bytes,profile,duration_ms,bitrate FROM audio_artifact WHERE account=? AND song_id=? ORDER BY profile LIMIT 3",new String[]{account,song});try{while(cursor.moveToNext())result.add(new Row(cursor.getString(0),cursor.getString(1),cursor.getLong(2),cursor.getInt(3),cursor.getLong(4),cursor.getLong(5)));}finally{cursor.close();}return result;}
}
