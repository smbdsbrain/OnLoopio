package io.onloopio;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.test.RenamingDelegatingContext;
import io.onloopio.db.MetadataStore;
import io.onloopio.model.Library;
import io.onloopio.model.LikeChange;
import io.onloopio.model.ListenEvent;
import io.onloopio.model.Song;
import io.onloopio.sync.BacksyncEngine;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Real SQLite durability and reconciliation, isolated from the user's library/account. */
public final class FeedbackStoreTest extends InstrumentationTestCase {
    private Context target,isolated;private MetadataStore store;
    private static Song song(String id){return new Song(id,id,"Artist","Album","mp3",60);}
    protected void setUp()throws Exception {
        super.setUp();target=getInstrumentation().getTargetContext();target.deleteDatabase("feedback_test_onloopio.db");
        isolated=new RenamingDelegatingContext(target,"feedback_test_");store=new MetadataStore(isolated);store.selectAccount("account");
        Library catalog=new Library();catalog.songs.add(song("a"));store.replaceCatalog(catalog);
    }
    protected void tearDown()throws Exception {store.close();target.deleteDatabase("feedback_test_onloopio.db");super.tearDown();}
    public void testLikesCoalesceAndStaleAcknowledgementCannotEraseEdit(){
        assertTrue(store.toggleLike("account","a"));LikeChange old=store.pendingLikes("account",25).get(0);
        assertFalse(store.toggleLike("account","a"));store.acknowledgeLike(old);
        assertEquals(1,store.pendingLikes("account",25).size());assertFalse(store.pendingLikes("account",25).get(0).liked);
        store.applyStarredSnapshot("account",Collections.singletonList(song("a")));assertFalse("Remote snapshot overwrote dirty unlike",store.isLiked("a"));
        assertTrue(store.toggleLike("account","a"));store.acknowledgeLike(store.pendingLikes("account",25).get(0));
        store.applyStarredSnapshot("account",Collections.<Song>emptyList());assertFalse("Remote unlike not imported",store.isLiked("a"));assertNotNull(store.song("a"));
    }
    public void testListensSurviveRestartAndRepeatedSongCountsSeparateSessions(){
        ListenEvent first=new ListenEvent("one","account","a",1000);store.recordListening(first,31000);store.recordListening(first,31000);
        store.recordListening(new ListenEvent("two","account","a",50000),80000);assertEquals(2,store.audioState("a").plays);
        store.close();store=new MetadataStore(isolated);assertEquals(2,store.pendingListens("account",25).size());assertEquals(1000L,store.pendingListens("account",25).get(0).time);
        store.acknowledgeListens(Collections.singletonList(first));assertEquals(1,store.pendingListens("account",25).size());assertEquals(2,store.audioState("a").plays);
    }
    public void testAccountSwitchRetainsOutboxAndLocalLikes(){
        store.toggleLike("account","a");store.recordListening(new ListenEvent("one","account","a",1000),31000);
        store.toggleLike(null,"local:file");store.recordListening(new ListenEvent("local",null,"local:file",1000),31000);
        store.selectAccount("other");assertEquals(0,store.pendingFeedback("other"));assertFalse(store.isLiked("a"));assertTrue(store.isLiked("local:file"));assertEquals(1,store.audioState("local:file").plays);
        assertEquals(2,store.pendingFeedback("account"));store.selectAccount("account");assertTrue(store.isLiked("a"));assertEquals(2,store.pendingFeedback("account"));
    }
    public void testRefreshPreservesLocalLikesAndDirtyChangesAndAddsFavoriteMetadata(){
        store.toggleLike(null,"local:file");store.toggleLike("account","a");
        store.applyStarredSnapshot("account",Collections.singletonList(song("remote-only")));
        assertTrue(store.isLiked("a"));assertTrue(store.isLiked("local:file"));assertTrue(store.isLiked("remote-only"));assertNotNull(store.song("remote-only"));
        assertEquals(2,store.favoriteSongs().size());assertEquals(1,store.catalogCount());
        Library refreshed=new Library();refreshed.songs.add(song("a"));store.replaceCatalog(refreshed);assertTrue(store.isLiked("a"));assertTrue(store.isLiked("remote-only"));
        try{store.applyStarredSnapshot("account",Arrays.asList(song("bad"),song("bad")));fail("Duplicate snapshot accepted");}catch(IllegalArgumentException expected){}
        assertTrue(store.isLiked("remote-only"));assertNull(store.song("bad"));
    }
    public void testOfflineQueueDrainsWithoutDownloadsAndRetriesOnlyUnconfirmed()throws Exception {
        store.toggleLike("account","a");store.recordListening(new ListenEvent("one","account","a",1000),31000);assertEquals(0,store.pendingDownloads());
        final boolean[] fail={true};final int[] submissions={0};BacksyncEngine.Source source=new BacksyncEngine.Source(){
            public void guard(){}
            public void like(String id,boolean liked){}
            public void scrobble(List<ListenEvent> events)throws IOException{submissions[0]++;assertEquals(1000L,events.get(0).time);if(fail[0])throw new IOException("Disconnected");}
            public List<Song> starred(){return Collections.singletonList(song("a"));}
        };
        assertFalse(new BacksyncEngine().check(store,"account",source).success());assertEquals(1,store.pendingFeedback("account"));assertTrue(store.isLiked("a"));
        store.close();store=new MetadataStore(isolated);fail[0]=false;assertEquals(0,store.pendingListens("account",10).size());assertEquals(1,store.pendingFeedback("account"));new io.onloopio.db.FeedbackStore(store).retry("account");assertTrue(new BacksyncEngine().check(store,"account",source).success());assertEquals(0,store.pendingFeedback("account"));
        new BacksyncEngine().check(store,"account",source);assertEquals(2,submissions[0]);
    }
    public void testV8MigrationPreservesMetadataQueueAndStatistics(){
        store.pin(Collections.singletonList("a"),true);store.listened("a",456);store.enqueueDownloads(Collections.singletonList("a"));
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.execSQL("DROP TABLE track_like");db.execSQL("DROP TABLE listen_event");for(String table:new String[]{"playback_session","queue_entry","attempt_ledger","offline_generation","offline_member","offline_pointer","listen_history","feedback_retry","audio_partial","replay_gain","audio_artifact","artifact_gain"})db.execSQL("DROP TABLE "+table);db.setVersion(8);store.close();store=new MetadataStore(isolated);
        assertEquals(20,store.getReadableDatabase().getVersion());assertNotNull(store.song("a"));assertTrue(store.audioState("a").pinned);assertEquals(456L,store.audioState("a").lastPlayed);assertEquals(1,store.audioState("a").plays);assertEquals(1,store.pendingDownloads());assertEquals(0,store.pendingFeedback("account"));
    }
}
