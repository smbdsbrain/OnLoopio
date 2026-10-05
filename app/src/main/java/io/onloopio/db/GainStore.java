package io.onloopio.db;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import io.onloopio.player.ReplayGain;
public final class GainStore {
    private final MetadataStore store;
    public GainStore(MetadataStore store){this.store=store;}
    static void schema(SQLiteDatabase db){db.execSQL("CREATE TABLE replay_gain(account TEXT NOT NULL,song_id TEXT NOT NULL,track REAL,album REAL,track_peak REAL,album_peak REAL,base REAL,fallback REAL,PRIMARY KEY(account,song_id))");}
    static void variants(SQLiteDatabase db){
        db.execSQL("ALTER TABLE replay_gain ADD COLUMN provenance TEXT NOT NULL DEFAULT 'legacy-unspecified'");
        db.execSQL("ALTER TABLE replay_gain ADD COLUMN source_stamp TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE TABLE artifact_gain(account TEXT NOT NULL,token TEXT NOT NULL,source_stamp TEXT NOT NULL,provenance TEXT NOT NULL,track REAL,album REAL,track_peak REAL,album_peak REAL,base REAL,fallback REAL,PRIMARY KEY(account,token))");
    }
    public void save(String account,String song,ReplayGain gain){store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO replay_gain(account,song_id,track,album,track_peak,album_peak,base,fallback) VALUES(?,?,?,?,?,?,?,?)",new Object[]{account,song,gain.track,gain.album,gain.trackPeak,gain.albumPeak,gain.base,gain.fallback});}
    public static String stamp(java.io.File file){return io.onloopio.model.CacheKey.hash(file.getAbsolutePath()+"\n"+file.length()+"\n"+file.lastModified());}
    public void saveLocal(String song,java.io.File file,ReplayGain gain){save("",song,gain);store.getWritableDatabase().execSQL("UPDATE replay_gain SET provenance='local-tags',source_stamp=? WHERE account='' AND song_id=?",new Object[]{stamp(file),song});}
    public boolean hasLocal(String song,java.io.File file){return android.database.DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM replay_gain WHERE account='' AND song_id=? AND source_stamp=? AND provenance='local-tags'",new String[]{song,stamp(file)})!=0;}
    public ReplayGain local(String song,java.io.File file){return hasLocal(song,file)?load("",song):empty();}
    public void saveArtifact(String account,String token,java.io.File file,ReplayGain gain,boolean normalized){store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO artifact_gain(account,token,source_stamp,provenance,track,album,track_peak,album_peak,base,fallback) VALUES(?,?,?,?,?,?,?,?,?,?)",new Object[]{account,token,stamp(file),normalized?"transcoded-normalization-unknown":"OpenSubsonic-original",gain.track,gain.album,gain.trackPeak,gain.albumPeak,gain.base,gain.fallback});}
    public ReplayGain artifact(String account,String token,java.io.File file){Cursor c=store.getReadableDatabase().rawQuery("SELECT track,album,track_peak,album_peak,base,fallback FROM artifact_gain WHERE account=? AND token=? AND source_stamp=? AND provenance='OpenSubsonic-original'",new String[]{account,token,stamp(file)});try{return c.moveToFirst()?new ReplayGain(optional(c,0),optional(c,1),optional(c,2),optional(c,3),optional(c,4),optional(c,5)):empty();}finally{c.close();}}
    private ReplayGain empty(){return new ReplayGain(null,null,null,null,null,null);}
    private Double optional(Cursor c,int n){return c.isNull(n)?null:c.getDouble(n);}
    public ReplayGain load(String account,String song){Cursor c=store.getReadableDatabase().rawQuery("SELECT track,album,track_peak,album_peak,base,fallback FROM replay_gain WHERE account=? AND song_id=?",new String[]{account,song});try{return c.moveToFirst()?new ReplayGain(optional(c,0),optional(c,1),optional(c,2),optional(c,3),optional(c,4),optional(c,5)):new ReplayGain(null,null,null,null,null,null);}finally{c.close();}}
}
