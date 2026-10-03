package io.onloopio;

import android.test.InstrumentationTestCase;
import android.test.RenamingDelegatingContext;
import io.onloopio.db.MetadataStore;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Song;
import io.onloopio.model.Library;
import java.util.Arrays;
import java.util.Collections;

/** Platform test runner supported on API 17; uses a separate test database. */
public final class MetadataTest extends InstrumentationTestCase {
    private MetadataStore store;
    protected void setUp() throws Exception {
        super.setUp();
        store = new MetadataStore(new RenamingDelegatingContext(getInstrumentation().getTargetContext(),"test_"));
        store.selectAccount("test-account-"+System.nanoTime()); store.replacePlaylists(Collections.<Playlist>emptyList());
    }
    protected void tearDown() throws Exception { store.close(); super.tearDown(); }
    private Playlist playlist(String id,String name,int count) { return new Playlist(id,name,"revision",count,12); }
    private Song song(String id) { return new Song(id,id,"artist","album","mp3",4); }
    public void testScalarDefaultsAndAudioIndexBackfill() {
        assertEquals(0,store.catalogCount());assertEquals(0,store.localCount());assertEquals(0,store.pendingDownloads());assertEquals(0,store.downloadCount(0));assertEquals(0,store.pinnedCount());
        assertEquals(0L,store.lastRefresh());assertEquals(0L,store.lastPlaylistCheck());assertEquals(0L,store.lastPlaylistAudit());
        assertNull(store.nextDownload());assertFalse(store.catalogSynced());assertFalse(store.needsAudioIndex());assertFalse(store.hasCatalogSong("missing"));assertFalse(store.followsPlaylist("missing"));assertFalse(store.hasPlaylistDetail("missing"));assertFalse(store.isLiked("missing"));assertFalse(store.protectedFromCleanup("missing"));assertEquals(0,store.pendingFeedback(null));
        Playlist p=playlist("scalar","Scalar",2);store.replacePlaylists(Arrays.asList(p));store.saveDetail(new PlaylistDetail(p,Arrays.asList(song("a"),song("b"))));
        assertEquals(2,store.catalogCount());assertTrue(store.hasPlaylistDetail(p.id));assertFalse(store.needsAudioIndex());
        store.getWritableDatabase().execSQL("UPDATE song SET audio_name='' WHERE id='a'");assertTrue(store.needsAudioIndex());store.backfillAudioIndex();assertFalse(store.needsAudioIndex());assertNotNull(store.song("a"));
        store.enqueueDownloads(Arrays.asList("a","b"));assertEquals("a",store.nextDownload());assertEquals(2,store.pendingDownloads());assertEquals(2,store.downloadCount(0));
        store.failedDownload("a");assertEquals("b",store.nextDownload());assertEquals(1,store.downloadCount(1));store.completedDownload("b");assertNull(store.nextDownload());assertEquals(1,store.pendingDownloads());assertEquals(1,store.downloadCount(2));store.retryDownloads();assertEquals("a",store.nextDownload());
        store.followPlaylist(p.id,true);store.pin(Arrays.asList("a"),true);assertTrue(store.followsPlaylist(p.id));assertTrue(store.protectedFromCleanup("b"));assertEquals(2,store.pinnedCount());store.stopFollowingPlaylists();assertEquals(1,store.pinnedCount());assertTrue(store.protectedFromCleanup("a"));assertFalse(store.protectedFromCleanup("b"));
    }
    public void testScalarQueriesWithoutServerRow() {
        android.content.Context actual=getInstrumentation().getTargetContext();String prefix="scalar_empty_"+System.nanoTime()+"_";MetadataStore empty=new MetadataStore(new RenamingDelegatingContext(actual,prefix));
        try{assertNull(empty.accountKey());assertNull(empty.nextDownload());assertEquals(0L,empty.lastRefresh());assertEquals(0L,empty.lastPlaylistCheck());assertEquals(0L,empty.lastPlaylistAudit());assertFalse(empty.catalogSynced());assertEquals(0,empty.catalogCount());assertFalse(empty.isLiked("missing"));}
        finally{empty.close();actual.deleteDatabase(prefix+"onloopio.db");}
    }
    public void testRepeatedSongsAndOrderPersist() {
        Playlist p=playlist("p","Mix",3);
        store.replacePlaylists(Arrays.asList(p));
        store.saveDetail(new PlaylistDetail(p,Arrays.asList(song("b"),song("a"),song("b"))));
        assertEquals("b",store.detail("p").songs.get(0).id);
        assertEquals("a",store.detail("p").songs.get(1).id);
        assertEquals("b",store.detail("p").songs.get(2).id);
        store.close();
        RenamingDelegatingContext reopened = new RenamingDelegatingContext(getInstrumentation().getTargetContext(),"test_");
        reopened.makeExistingFilesAndDbsAccessible();
        store = new MetadataStore(reopened);
        assertEquals(3,store.detail("p").songs.size());
    }
    public void testListReplacementRollsBackOnDuplicate() {
        store.replacePlaylists(Arrays.asList(playlist("p","Old",1)));
        try { store.replacePlaylists(Arrays.asList(playlist("q","New",1),playlist("q","Duplicate",1))); fail("Accepted duplicate ID"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(1,store.playlists().size()); assertEquals("Old",store.playlists().get(0).name);
    }
    public void testRenameAndRemoveDoNotDamageSharedMetadata() {
        Playlist a=playlist("a","First",1),b=playlist("b","Second",1);
        store.replacePlaylists(Arrays.asList(a,b));
        store.saveDetail(new PlaylistDetail(a,Arrays.asList(song("shared"))));
        store.saveDetail(new PlaylistDetail(b,Arrays.asList(song("shared"))));
        store.replacePlaylists(Arrays.asList(playlist("b","Renamed",1)));
        assertNull(store.detail("a")); assertEquals("Renamed",store.playlists().get(0).name);
        assertEquals("shared",store.detail("b").songs.get(0).id);
    }
    public void testAccountChangeClearsOtherUsersCache() {
        store.replacePlaylists(Arrays.asList(playlist("p","Private",1)));
        store.selectAccount("different-account"); assertTrue(store.playlists().isEmpty());
        assertEquals(0,store.lastRefresh());
    }
    public void testEmptyPlaylistIsCached() {
        Playlist empty=playlist("empty","Empty",0);
        store.saveDetail(new PlaylistDetail(empty,Collections.<Song>emptyList()));
        assertNotNull(store.detail("empty")); assertEquals(0,store.detail("empty").songs.size());
    }
    public void testLibraryDeduplicatesSharedSongsAndRetainsOfflineMetadata() {
        Playlist a=playlist("a","One",2),b=playlist("b","Two",1);
        store.saveDetail(new PlaylistDetail(a,Arrays.asList(song("shared"),song("removed"))));
        store.saveDetail(new PlaylistDetail(b,Arrays.asList(song("shared"))));
        assertEquals(2,store.songs().size()); store.replacePlaylists(Arrays.asList(b));
        assertEquals(2,store.songs().size()); assertNotNull(store.song("removed"));
    }
    public void testAlbumIdsAndTrackOrderPersist() {
        Playlist a=playlist("a","Albums",2);
        Song one=new Song("one","Same","artist","Same album","mp3",10,"album-one",2);
        Song two=new Song("two","Same","artist","Same album","mp3",10,"album-two",1);
        store.saveDetail(new PlaylistDetail(a,Arrays.asList(one,two)));
        assertFalse(store.song("one").albumKey().equals(store.song("two").albumKey()));
        assertEquals(2,store.song("one").track);
    }
    public void testCatalogSnapshotAndDownloadQueueSurviveServerRemoval() {
        Library full=new Library(); full.artists.add(new Library.Entity("ar","Artist","","")); full.albums.add(new Library.Entity("al","Album","ar","Artist"));
        full.songs.add(new Song("x","Track","Artist","Album","opus",45,"al",1,"ar","Electronic",1));
        store.replaceCatalog(full);assertEquals(1,store.catalogSongs().size());assertEquals("Electronic",store.song("x").genre);
        assertEquals(1,store.offlineSongs(Arrays.asList(io.onloopio.model.CacheKey.audioName("x"))).size());assertTrue(store.offlineSongs(Arrays.asList("missing.audio")).isEmpty());
        store.enqueueDownloads(Arrays.asList("x","x"));assertEquals(1,store.pendingDownloads());assertEquals("x",store.nextDownload());
        store.failedDownload("x");assertNull(store.nextDownload());store.retryDownloads();assertEquals("x",store.nextDownload());
        store.replaceCatalog(new Library());assertTrue(store.catalogSongs().isEmpty());assertNotNull("Offline metadata lost",store.song("x"));
        store.completedDownload("x");assertEquals(0,store.pendingDownloads());
    }
    public void testCoverAndListeningPolicySurviveCatalogReplacement(){
        Song song=new Song("cover","Title","Artist","Album","mp3",60,"album",1,"artist","Jazz",1,"mf-cover");Library library=new Library();library.songs.add(song);store.replaceCatalog(library);store.pin(Arrays.asList(song.id),true);store.downloaded(song.id,123L);store.listened(song.id,456L);store.replaceCatalog(library);
        assertEquals("mf-cover",store.song(song.id).coverArt);assertTrue(store.audioState(song.id).pinned);assertEquals(456L,store.audioState(song.id).lastPlayed);assertEquals(1,store.audioState(song.id).plays);
    }
    public void testV5MigrationRetainsCatalogAndAddsCoverAndPolicyState()throws Exception{
        android.content.Context target=getInstrumentation().getTargetContext();String prefix="migration_test_";java.io.File copy=target.getDatabasePath(prefix+"onloopio.db");target.deleteDatabase(prefix+"onloopio.db");
        android.database.sqlite.SQLiteDatabase legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(copy,null);
        try{
            legacy.execSQL("CREATE TABLE server (id INTEGER PRIMARY KEY,account_key TEXT,last_successful_sync INTEGER,catalog_synced INTEGER)");legacy.execSQL("INSERT INTO server VALUES (1,'fixture',555,1)");
            legacy.execSQL("CREATE TABLE playlist (id TEXT PRIMARY KEY,name TEXT NOT NULL,changed TEXT NOT NULL,song_count INTEGER NOT NULL,duration INTEGER NOT NULL,detail_cached INTEGER NOT NULL DEFAULT 0)");
            legacy.execSQL("CREATE TABLE song (id TEXT PRIMARY KEY,title TEXT,artist TEXT,album TEXT,suffix TEXT,duration INTEGER,album_id TEXT,track INTEGER,artist_id TEXT,genre TEXT,disc INTEGER,in_catalog INTEGER,audio_name TEXT)");
            legacy.execSQL("INSERT INTO song VALUES ('legacy','Title','Artist','Album','mp3',60,'album',1,'artist','Jazz',1,1,'fixture.audio')");
            legacy.execSQL("CREATE TABLE download_queue (song_id TEXT PRIMARY KEY,position INTEGER,state INTEGER)");legacy.execSQL("INSERT INTO download_queue VALUES ('legacy',0,0)");legacy.setVersion(5);
        }finally{legacy.close();}
        RenamingDelegatingContext context=new RenamingDelegatingContext(target,prefix);context.makeExistingFilesAndDbsAccessible();MetadataStore upgraded=new MetadataStore(context);
        try{assertEquals(9,upgraded.getReadableDatabase().getVersion());assertEquals(1,upgraded.catalogCount());Song song=upgraded.song("legacy");assertEquals("Title",song.title);assertEquals("",song.coverArt);assertEquals("legacy",upgraded.nextDownload());assertEquals(-1L,upgraded.downloads().get(0).total);upgraded.pin(Arrays.asList(song.id),true);assertTrue(upgraded.audioState(song.id).pinned);assertEquals("Migration should refresh missing covers",0L,upgraded.lastRefresh());}
        finally{upgraded.close();target.deleteDatabase(prefix+"onloopio.db");}
    }
}
