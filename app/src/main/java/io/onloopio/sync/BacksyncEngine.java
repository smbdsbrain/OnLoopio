package io.onloopio.sync;

import io.onloopio.model.LikeChange;
import io.onloopio.model.ListenEvent;
import io.onloopio.model.Song;
import java.io.IOException;
import java.util.List;

/** Durable feedback uses the same guarded worker as playlist checks. */
public final class BacksyncEngine {
    public static final int BATCH_SIZE=25;
    public interface Repository {
        List<LikeChange> pendingLikes(String account,int limit);
        void acknowledgeLike(LikeChange change);
        List<ListenEvent> pendingListens(String account,int limit);
        void acknowledgeListens(List<ListenEvent> events);
        void applyStarredSnapshot(String account,List<Song> songs);
        default void failedLike(LikeChange change,IOException error){}
        default void failedListen(ListenEvent event,IOException error){}
    }
    public interface Source {
        void guard()throws IOException;
        void like(String id,boolean liked)throws IOException;
        void scrobble(List<ListenEvent> events)throws IOException;
        List<Song> starred()throws IOException;
    }
    public static final class Result {
        public int likes,listens;
        public boolean likesFailed,listensFailed,starredFailed,accountFailed;
        public boolean success(){return !likesFailed && !listensFailed && !starredFailed;}
    }
    public Result check(Repository store,String account,Source source)throws IOException {
        Result result=new Result();
        // Bound the snapshot: events arriving during requests belong to a later pass.
        List<LikeChange> likes=store.pendingLikes(account,1000);
        for(LikeChange change:likes) {
            source.guard();
            try {source.like(change.songId,change.liked);source.guard();store.acknowledgeLike(change);result.likes++;}
            catch(IOException failed){source.guard();store.failedLike(change,failed);result.likesFailed=true;if("account".equals(FeedbackRetry.kind(failed))){result.accountFailed=true;return result;}}
        }
        List<ListenEvent> listens=store.pendingListens(account,1000);
        for(ListenEvent event:listens) {
            source.guard();List<ListenEvent> batch=java.util.Collections.singletonList(event);
            try {source.scrobble(batch);source.guard();store.acknowledgeListens(batch);result.listens++;}
            catch(IOException failed){source.guard();store.failedListen(event,failed);result.listensFailed=true;if("account".equals(FeedbackRetry.kind(failed))){result.accountFailed=true;return result;}}
        }
        source.guard();
        try {List<Song> songs=source.starred();source.guard();store.applyStarredSnapshot(account,songs);}
        catch(IOException failed){source.guard();result.starredFailed=true;result.accountFailed="account".equals(FeedbackRetry.kind(failed));}
        return result;
    }
}
