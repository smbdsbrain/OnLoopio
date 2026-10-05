package io.onloopio;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.InstrumentationTestCase;
import android.test.RenamingDelegatingContext;
import io.onloopio.api.ServerConfig;
import io.onloopio.db.MetadataStore;
import io.onloopio.library.AudioFileIndex;
import io.onloopio.library.LocalMusicScanner;
import io.onloopio.library.MusicPaths;
import io.onloopio.model.Song;
import io.onloopio.player.AudioCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;

/** Every destructive check has its own database, account and verified Music subtree. */
public final class MusicStorageTest extends InstrumentationTestCase {
    private Context context,actual;private AudioCache cache;private MetadataStore store;private File fixture;private String artist,prefix;private ServerConfig config;
    protected void setUp()throws Exception{
        super.setUp();actual=getInstrumentation().getTargetContext();prefix="music_test_"+System.nanoTime()+"_";
        context=new ContextWrapper(new RenamingDelegatingContext(actual,prefix)){public Context getApplicationContext(){return this;}};
        artist="_OnLoopio test "+System.nanoTime();fixture=new File(MusicPaths.root(),artist);assertTrue(fixture.mkdirs());
        config=new ServerConfig("https://fixture.invalid",prefix,"fixture");cache=new AudioCache(context,config);store=new MetadataStore(context);store.selectAccount(config.accountKey());
    }
    protected void tearDown()throws Exception{
        try{cache.clear();store.close();deleteFixture(fixture);cache.legacyDirectory().delete();actual.deleteDatabase(prefix+"onloopio.db");actual.deleteDatabase(prefix+"audio-files.db");}finally{super.tearDown();}
    }
    private void deleteFixture(File f)throws Exception{if(!f.getCanonicalPath().equals(fixture.getCanonicalPath()) && !f.getCanonicalPath().startsWith(fixture.getCanonicalPath()+File.separator))throw new IllegalStateException("Outside fixture");if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File child:children)deleteFixture(child);}if(f.exists())assertTrue(f.delete());}
    private Song song(String id,String title,String suffix){return new Song(prefix+id,title,artist,"Album",suffix,12,"",1,"","Test",1,"");}
    private void copy(File target,String asset)throws Exception{assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());InputStream in=getInstrumentation().getContext().getAssets().open("music/"+asset);FileOutputStream out=new FileOutputStream(target);try{byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);out.getFD().sync();}finally{out.close();in.close();}}
    public void testPortablePathsAndContainment()throws Exception{
        assertEquals("_CON",MusicPaths.component("CON","unknown",64));assertEquals("_LPT1.txt",MusicPaths.component("LPT1.txt","unknown",64));
        assertEquals("AC - DC",MusicPaths.component(" AC/DC. ","unknown",64));assertEquals("unknown",MusicPaths.component("...","unknown",64));
        Song s=new Song("x","A: track?","Artist/Name","Album*Name","mp3",12,"",3,"","",2,"");String name=MusicPaths.relative(s,"mp3");assertTrue(name,name.endsWith("2-03 - A - track -.mp3"));
        try{MusicPaths.resolve(fixture,"../../outside.mp3");fail("Escaped Music");}catch(java.io.IOException expected){}
    }
    public void testMigrationPreservesAudioIsIdempotentAndDoesNotOverwriteImports()throws Exception{
        Song a=song("a","Same title","m4a"),b=song("b","Same title","mp3");File imported=MusicPaths.resolve(MusicPaths.root(),MusicPaths.relative(a,"mp3"));copy(imported,"fixture.mp3");long size=imported.length();
        for(Song s:Arrays.asList(a,b))copy(cache.file(s.id),"fixture.mp3");long modified=cache.file(a.id).lastModified();
        assertEquals(2,cache.legacyNames().size());assertEquals(2,cache.migrate(Arrays.asList(a,b)));assertTrue(cache.legacyNames().isEmpty());File first=cache.file(a.id),second=cache.file(b.id);
        assertTrue(first.getPath().startsWith(fixture.getPath()));assertTrue(first.getName().endsWith(".mp3"));assertFalse(first.equals(imported));assertFalse(first.equals(second));assertEquals(size,first.length());assertEquals(modified,first.lastModified());assertEquals(size,imported.length());assertTrue(cache.contains(a));
        assertEquals(0,cache.migrate(Arrays.asList(a,b)));AudioCache reopened=new AudioCache(context,config);assertEquals(first,reopened.file(a.id));assertEquals(2,reopened.completedNames().size());
        cache.clear();assertTrue("Cache clearing deleted imported music",imported.isFile());assertFalse(first.exists());assertFalse(second.exists());
    }
    public void testReplacedOrRenamedDownloadBecomesUserOwned()throws Exception{
        Song a=song("a","Replace","mp3"),b=song("b","Rename","mp3");for(Song s:Arrays.asList(a,b))copy(cache.file(s.id),"fixture.mp3");cache.migrate(Arrays.asList(a,b));
        File replaced=cache.file(a.id);FileOutputStream append=new FileOutputStream(replaced,true);try{append.write(7);}finally{append.close();}
        File renamed=new File(fixture,"Album/Personal song.mp3");assertTrue(cache.file(b.id).renameTo(renamed));cache.refresh();assertFalse(cache.contains(a));assertFalse(cache.contains(b));cache.clear();assertTrue(replaced.isFile());assertTrue(renamed.isFile());
        assertEquals(2,new LocalMusicScanner().scan(fixture,Collections.<String>emptySet(),Collections.<MetadataStore.LocalEntry>emptyList(),null).size());
    }
    public void testFatTimestampRoundingKeepsOwnershipAfterRemount()throws Exception{
        Song s=song("fat","FAT precision","mp3");File old=cache.file(s.id);copy(old,"fixture.mp3");long tick=System.currentTimeMillis()/2000*2000;assertTrue(old.setLastModified(tick+1000));assertEquals(1,cache.migrate(Arrays.asList(s)));
        File published=cache.file(s.id);assertTrue(published.setLastModified(tick));cache.refresh();assertTrue("FAT remount released owned download",cache.contains(s));AudioFileIndex index=new AudioFileIndex(context);
        try{java.util.Set<String> owned=index.ownedPaths();assertTrue(owned.contains(published.getCanonicalPath().toUpperCase(java.util.Locale.US)));assertEquals(0,new LocalMusicScanner().scan(fixture,owned,Collections.<MetadataStore.LocalEntry>emptyList(),null).size());}finally{index.close();}
        assertTrue(published.setLastModified(tick+4000));cache.clear();assertTrue("A later replacement must remain user-owned",published.isFile());
    }
    public void testIncrementalScanningTagsExclusionsAndTransactionalSnapshot()throws Exception{
        File tagged=new File(fixture,"Album/01 - tagged.mp3"),fallback=new File(fixture,"Album/02 - Folder title.wav"),partial=new File(fixture,"unfinished.mp3.part"),managed=new File(fixture,"Album/03 - managed.mp3");
        copy(tagged,"fixture.mp3");copy(fallback,"fixture.wav");copy(partial,"fixture.mp3");copy(managed,"fixture.mp3");
        java.util.Set<String> owned=new HashSet<String>();owned.add(managed.getCanonicalPath());LocalMusicScanner scanner=new LocalMusicScanner();
        List<MetadataStore.LocalEntry> first=scanner.scan(fixture,owned,Collections.<MetadataStore.LocalEntry>emptyList(),null);assertEquals(2,first.size());
        Song mp3=null,wav=null;for(MetadataStore.LocalEntry e:first)if(e.song.suffix.equals("mp3"))mp3=e.song;else wav=e.song;
        assertNotNull(mp3);assertEquals("QA Tagged Track",mp3.title);assertEquals("OnLoopio QA Artist",mp3.artist);assertEquals("QA Album",mp3.album);assertTrue(mp3.duration>=11);assertTrue(mp3.id.startsWith("local:"));assertEquals("Folder title",wav.title);
        List<MetadataStore.LocalEntry> second=scanner.scan(fixture,owned,first,null);for(MetadataStore.LocalEntry e:second){boolean reused=false;for(MetadataStore.LocalEntry old:first)if(e==old)reused=true;assertTrue("Unchanged tags re-read",reused);}
        store.replaceLocalSongs(first);store.listened(mp3.id,456);store.selectAccount("other account");assertEquals(2,store.localCount());assertEquals(456L,store.audioState(mp3.id).lastPlayed);store.enqueueDownloads(Arrays.asList(mp3.id));assertEquals(0,store.pendingDownloads());
        try{store.replaceLocalSongs(Arrays.asList(first.get(0),new MetadataStore.LocalEntry(song("bad","Bad","mp3"),16,0)));fail("Partial snapshot committed");}catch(IllegalArgumentException expected){}assertEquals(2,store.localCount());
        try{scanner.scan(new File(fixture,"unmounted"),owned,first,null);fail("Unavailable root accepted");}catch(java.io.IOException expected){}assertEquals(2,store.localCount());
        assertTrue(tagged.delete());store.replaceLocalSongs(scanner.scan(fixture,owned,first,null));assertEquals(1,store.localCount());
    }
    public void testV7MigrationRetainsFollowedPlaylistsAndAudioHistory()throws Exception{
        store.getWritableDatabase().execSQL("INSERT INTO playlist VALUES ('p','Saved','r',1,12,1,1)");store.getWritableDatabase().execSQL("INSERT INTO audio_state VALUES ('s',123,456,3,1)");
        for(String table:new String[]{"local_song","track_like","listen_event","playback_session","queue_entry","attempt_ledger","offline_member","offline_generation","offline_pointer","listen_history","feedback_retry","audio_partial","replay_gain","audio_artifact","artifact_gain"})store.getWritableDatabase().execSQL("DROP TABLE "+table);store.getWritableDatabase().setVersion(7);store.close();store=new MetadataStore(context);
        assertEquals(20,store.getReadableDatabase().getVersion());assertTrue(store.followsPlaylist("p"));assertEquals(456L,store.audioState("s").lastPlayed);assertEquals(3,store.audioState("s").plays);assertTrue(store.audioState("s").pinned);assertEquals(0,store.localCount());
    }
}
