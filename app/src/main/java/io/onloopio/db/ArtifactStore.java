package io.onloopio.db;
import android.database.sqlite.SQLiteDatabase;
import io.onloopio.player.AudioProfile;
import io.onloopio.model.Song;
import java.util.List;
public final class ArtifactStore {
    private final MetadataStore store;
    public ArtifactStore(MetadataStore s){store=s;}
    static void schema(SQLiteDatabase db){db.execSQL("CREATE TABLE audio_artifact(account TEXT NOT NULL,token TEXT NOT NULL,song_id TEXT NOT NULL,profile INTEGER NOT NULL,actual TEXT NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(account,token))");db.execSQL("CREATE INDEX audio_artifact_song ON audio_artifact(account,song_id)");}
    public void record(String account,Song song,int profile,String actual,long bytes){store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO audio_artifact(account,token,song_id,profile,actual,bytes) VALUES(?,?,?,?,?,?)",new Object[]{account,AudioProfile.token(song.id,profile),song.id,profile,actual,bytes});}
    public static final class Row{public final String token,actual;public final long bytes;public final int profile;Row(String t,String a,long b,int p){token=t;actual=a;bytes=b;profile=p;}}
    public java.util.List<Row> rows(String account,String song){java.util.List<Row> result=new java.util.ArrayList<Row>();android.database.Cursor cursor=store.getReadableDatabase().rawQuery("SELECT token,actual,bytes,profile FROM audio_artifact WHERE account=? AND song_id=? ORDER BY profile LIMIT 3",new String[]{account,song});try{while(cursor.moveToNext())result.add(new Row(cursor.getString(0),cursor.getString(1),cursor.getLong(2),cursor.getInt(3)));}finally{cursor.close();}return result;}
}
