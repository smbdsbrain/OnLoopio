package io.onloopio;

import android.test.InstrumentationTestCase;
import android.test.RenamingDelegatingContext;
import io.onloopio.db.MetadataStore;
import io.onloopio.model.CacheKey;
import io.onloopio.model.Library;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Song;
import io.onloopio.sync.PlaylistSyncEngine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** AudioMuse-like updates against isolated SQLite; no live playlist writes or audio deletion. */
public final class PlaylistSyncTest extends InstrumentationTestCase {
    private MetadataStore store;private FakeSource source;private long now;
    protected void setUp()throws Exception{super.setUp();store=new MetadataStore(new RenamingDelegatingContext(getInstrumentation().getTargetContext(),"sync_test_"));store.selectAccount("fixture-"+System.nanoTime());store.replaceCatalog(new Library());source=new FakeSource();now=System.currentTimeMillis();}
    protected void tearDown()throws Exception{store.close();super.tearDown();}
    private static Song song(String id){return new Song(id,id,"Artist","Album","mp3",60);}
    private static PlaylistDetail detail(String id,String revision,String... ids){List<Song> songs=new ArrayList<Song>();for(String value:ids)songs.add(song(value));return new PlaylistDetail(new Playlist(id,id,revision,ids.length,ids.length*60),songs);}
    private PlaylistSyncEngine.Result check(long at)throws Exception{return new PlaylistSyncEngine().check(store,store.accountKey(),source,Arrays.asList(CacheKey.audioName("a"),CacheKey.audioName("b")),at,false,Collections.<String>emptySet());}
    private final class FakeSource implements PlaylistSyncEngine.Source {
        final Map<String,PlaylistDetail> entries=new java.util.LinkedHashMap<String,PlaylistDetail>();int calls,guards;String fail;int cancelAt;boolean catalogFailure;
        public void guard()throws IOException{if(++guards==cancelAt)throw new IOException("Fixture cancellation");}
        public List<Playlist> playlists(){List<Playlist> rows=new ArrayList<Playlist>();for(PlaylistDetail d:entries.values())rows.add(d.playlist);return rows;}
        public PlaylistDetail detail(String id)throws IOException{calls++;if(id.equals(fail))throw new IOException("Fixture network failure");return entries.get(id);}
        public Library catalog()throws IOException{if(catalogFailure)throw new IOException("Fixture catalog failure");Library result=new Library();java.util.Set<String> ids=new java.util.HashSet<String>();for(PlaylistDetail d:entries.values())for(Song song:d.songs)if(ids.add(song.id))result.songs.add(song);return result;}
    }
    public void testChangedPlaylistSameSizePreservesOrderDuplicatesAndQueuesOnlyMissing()throws Exception{
        source.entries.put("mix",detail("mix","one","a","old","b","a"));check(now);store.followPlaylist("mix",true);source.calls=0;
        source.entries.put("mix",detail("mix","two","b","new","b","a"));PlaylistSyncEngine.Result result=check(now+1000);
        assertEquals(1,result.refreshed);assertEquals(1,result.queued);assertEquals(1,store.pendingDownloads());assertEquals("new",store.nextDownload());
        assertEquals("b",store.detail("mix").songs.get(0).id);assertEquals("new",store.detail("mix").songs.get(1).id);assertEquals("b",store.detail("mix").songs.get(2).id);
        assertTrue(store.followsPlaylist("mix"));assertNotNull("Removed audio metadata lost",store.song("old"));assertFalse("Incomplete legacy generation was not ready",store.protectedFromCleanup("old"));assertTrue(store.protectedFromCleanup("new"));
        assertEquals(0,check(now+2000).queued);assertEquals("Unchanged playlist fetched again",1,source.calls);
    }
    public void testUnchangedAndNewPlaylistUseIncrementalRequests()throws Exception{
        source.entries.put("one",detail("one","r","a"));check(now);source.calls=0;assertEquals(0,check(now+1000).refreshed);assertEquals(0,source.calls);
        source.entries.put("two",detail("two","r","b"));assertEquals(1,check(now+2000).refreshed);assertEquals(1,source.calls);
    }
    public void testNewLibraryMemberDoesNotRequireCatalog()throws Exception{
        source.entries.put("mix",detail("mix","old","a"));check(now);source.entries.put("mix",detail("mix","new","a","brand-new"));assertFalse(check(now+1000).catalog);assertNotNull(store.song("brand-new"));assertFalse(store.hasCatalogSong("brand-new"));assertEquals(2,store.songs().size());
    }
    public void testFailureMidwayKeepsAllOldPlaylistsMembershipAndSuccessStamp()throws Exception{
        source.entries.put("one",detail("one","old","a"));source.entries.put("two",detail("two","old","b"));check(now);store.followPlaylist("one",true);
        source.entries.put("one",detail("one","new","added"));source.entries.put("two",detail("two","new","added-two"));source.fail="two";
        try{check(now+1000);fail("Committed a partial response");}catch(IOException expected){}
        assertEquals("a",store.detail("one").songs.get(0).id);assertEquals("b",store.detail("two").songs.get(0).id);assertEquals(now,store.lastPlaylistCheck());assertEquals(0,store.pendingDownloads());assertNull(store.song("added"));
    }
    public void testDailyAuditDetectsMembershipEvenWithUnchangedRevision()throws Exception{
        source.entries.put("mix",detail("mix","unchanged","a"));check(now);source.entries.put("mix",detail("mix","unchanged","b"));source.calls=0;
        check(now+1000);assertEquals("a",store.detail("mix").songs.get(0).id);assertEquals(0,source.calls);
        check(now+PlaylistSyncEngine.AUDIT_INTERVAL+1);assertEquals("b",store.detail("mix").songs.get(0).id);assertEquals(1,source.calls);
    }
    public void testMissingRevisionAlwaysChecksAndRemovedPlaylistReleasesItsOwnership()throws Exception{
        source.entries.put("one",detail("one","","a"));source.entries.put("two",detail("two","","a"));check(now);store.followPlaylist("one",true);store.followPlaylist("two",true);
        source.calls=0;check(now+1000);assertEquals(2,source.calls);source.entries.remove("one");check(now+2000);assertNotNull(store.detail("one"));assertTrue(store.protectedFromCleanup("a"));
        source.entries.remove("two");check(now+3000);assertTrue("Detached ready snapshots retained",store.protectedFromCleanup("a"));new io.onloopio.db.GenerationStore(store).release(store.accountKey(),"one");new io.onloopio.db.GenerationStore(store).release(store.accountKey(),"two");assertFalse(store.protectedFromCleanup("a"));assertNotNull(store.song("a"));
    }
    public void testCancellationBeforeCommitAndWrongAccountCannotPublish()throws Exception{
        source.entries.put("mix",detail("mix","old","a"));check(now);source.entries.put("mix",detail("mix","new","b"));source.guards=0;source.cancelAt=3;
        try{check(now+1000);fail("Cancellation ignored");}catch(IOException expected){}assertEquals("a",store.detail("mix").songs.get(0).id);
        source.cancelAt=0;try{new PlaylistSyncEngine().check(store,"other-account",source,Collections.<String>emptyList(),now+2000,false,Collections.<String>emptySet());fail("Account guard ignored");}catch(IllegalStateException expected){}
        assertEquals("a",store.detail("mix").songs.get(0).id);assertEquals(now,store.lastPlaylistCheck());
    }
    public void testForcedRefreshAndEmptyPlaylistAreValid()throws Exception{
        source.entries.put("empty",detail("empty","stable"));check(now);assertNotNull(store.detail("empty"));store.followPlaylist("empty",true);
        source.calls=0;new PlaylistSyncEngine().check(store,store.accountKey(),source,Collections.<String>emptyList(),now+1000,false,Collections.singleton("empty"));assertEquals(1,source.calls);assertEquals(0,store.pendingDownloads());assertTrue(store.followsPlaylist("empty"));
    }
    public void testFullCatalogFailureStillPublishesPlaylists()throws Exception{source.entries.put("mix",detail("mix","old","a"));check(now);source.entries.put("mix",detail("mix","new","b"));source.catalogFailure=true;PlaylistSyncEngine.Result r=new PlaylistSyncEngine().check(store,store.accountKey(),source,Collections.<String>emptyList(),now+1000,true,Collections.<String>emptySet());assertTrue(r.catalogFailed);assertEquals("b",store.detail("mix").songs.get(0).id);assertEquals(now+1000,store.lastPlaylistCheck());assertTrue(store.hasCatalogSong("a")==false);}
    public void testV6MigrationPreservesPlaylistsPinsAndCatalogTimestamp()throws Exception{
        android.content.Context target=getInstrumentation().getTargetContext();String filename="sync_v6_test_onloopio.db";target.deleteDatabase(filename);
        android.database.sqlite.SQLiteDatabase legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(target.getDatabasePath(filename),null);
        try{legacy.execSQL("CREATE TABLE server (id INTEGER PRIMARY KEY,account_key TEXT,last_successful_sync INTEGER,catalog_synced INTEGER)");legacy.execSQL("INSERT INTO server VALUES (1,'fixture',555,1)");
            legacy.execSQL("CREATE TABLE playlist (id TEXT PRIMARY KEY,name TEXT NOT NULL,changed TEXT NOT NULL,song_count INTEGER NOT NULL,duration INTEGER NOT NULL,detail_cached INTEGER NOT NULL DEFAULT 0)");legacy.execSQL("INSERT INTO playlist VALUES ('p','Existing','r',0,0,1)");
            legacy.execSQL("CREATE TABLE audio_state (song_id TEXT PRIMARY KEY,downloaded_at INTEGER,last_played INTEGER,play_count INTEGER,pinned INTEGER)");legacy.execSQL("INSERT INTO audio_state VALUES ('a',123,456,2,1)");legacy.setVersion(6);
        }finally{legacy.close();}
        RenamingDelegatingContext context=new RenamingDelegatingContext(target,"sync_v6_test_");context.makeExistingFilesAndDbsAccessible();MetadataStore migrated=new MetadataStore(context);
        try{assertEquals(19,migrated.getReadableDatabase().getVersion());assertEquals("Existing",migrated.playlists().get(0).name);assertEquals(555L,migrated.lastRefresh());assertEquals(0L,migrated.lastPlaylistCheck());assertFalse(migrated.followsPlaylist("p"));migrated.followPlaylist("p",true);assertTrue(migrated.followsPlaylist("p"));assertTrue(migrated.audioState("a").pinned);assertEquals(456L,migrated.audioState("a").lastPlayed);}
        finally{migrated.close();target.deleteDatabase(filename);}
    }
}
