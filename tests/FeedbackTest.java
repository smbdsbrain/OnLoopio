import io.onloopio.model.LikeChange;
import io.onloopio.model.ListenEvent;
import io.onloopio.model.Song;
import io.onloopio.player.ListeningSession;
import io.onloopio.player.PlayGesture;
import io.onloopio.sync.BacksyncEngine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic playback and sync failure tests, without a device or live account. */
public final class FeedbackTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    public static void main(String[] args)throws Exception {
        listening();gestures();backsync();System.out.println("Feedback checks passed: "+checks);
    }
    private static void listening(){
        ListeningSession session=new ListeningSession();session.baseline(0,0);
        check(!session.sample(29000,29000,120000,true),"Before 30-second threshold");
        check(session.sample(30000,30000,120000,true),"30-second threshold");
        check(!session.sample(50000,50000,120000,true),"One listen per session");
        session.reset();session.baseline(0,0);check(session.sample(5000,5000,10000,true),"Half of a short track");
        session.reset();session.baseline(0,0);check(!session.sample(500,500,500,true),"Minimum one second");
        session.reset();session.baseline(0,0);check(!session.sample(29999,29999,0,true),"Unknown duration uses 30 seconds");check(session.sample(30000,30000,0,true),"Unknown duration threshold");
        session.reset();session.baseline(0,0);session.sample(5000,5000,120000,true);
        session.sample(25000,5000,120000,false);check(session.playedMillis()==5000,"Pause excluded");
        session.baseline(25000,100000);check(!session.sample(30000,105000,120000,true) && session.playedMillis()==10000,"Seek excluded by new baseline");
        session.sample(60000,105000,120000,true);check(session.playedMillis()==10000,"Buffering with stalled position excluded");
        session.sample(80000,125000,120000,true);check(session.playedMillis()==30000,"Real progress accumulates across resume");
        session.reset();session.baseline(80000,0);check(!session.sample(81000,1000,120000,true),"Repeat starts a fresh session");
    }
    private static int tap(PlayGesture gesture,long at,String context){gesture.key(true,false,0,at,at,context,false);return gesture.key(false,false,0,at,at+10,context,false);}
    private static void gestures(){
        PlayGesture g=new PlayGesture();check(tap(g,1000,"s")==PlayGesture.NONE,"First tap buffered");
        check(g.finish(1429,"s",false)==PlayGesture.NONE,"Single not premature");check(g.finish(1430,"s",false)==PlayGesture.SINGLE,"Single fires after window");
        check(tap(g,2000,"s")==PlayGesture.NONE && tap(g,2200,"s")==PlayGesture.DOUBLE,"Double replaces single");check(g.finish(3000,"s",false)==PlayGesture.NONE,"Double does not leak a toggle");
        tap(g,4000,"s");check(g.key(true,false,1,4100,4100,"s",false)==PlayGesture.NONE,"Autorepeat ignored");check(g.finish(4430,"s",false)==PlayGesture.SINGLE,"Autorepeat did not like");
        tap(g,5000,"s");tap(g,5000,"s");check(g.finish(5430,"s",false)==PlayGesture.SINGLE,"Duplicate deliveries ignored");
        tap(g,6000,"old");check(g.finish(6500,"new",false)==PlayGesture.NONE,"Track change cancels pending gesture");
        tap(g,7000,"s");check(g.finish(7500,"s",true)==PlayGesture.NONE,"Lock cancels pending gesture");
        g.key(true,false,0,8000,8000,"s",false);g.key(false,true,0,8000,8010,"s",false);check(g.finish(8500,"s",false)==PlayGesture.NONE,"Canceled key ignored");
        tap(g,9000,"s");g.cancel();check(g.finish(9500,"s",false)==PlayGesture.NONE,"Service cleanup cancels gesture");
        tap(g,10000,"s");check(g.key(true,false,0,11000,11000,"s",false)==PlayGesture.SINGLE,"Late second press resolves first single");
        g.key(false,false,0,11000,11010,"s",false);check(g.finish(11430,"s",false)==PlayGesture.SINGLE,"Late second press remains a single");
    }
    private static final class Store implements BacksyncEngine.Repository {
        final List<LikeChange> likes=new ArrayList<LikeChange>();final List<ListenEvent> listens=new ArrayList<ListenEvent>();int snapshots;
        public List<LikeChange> pendingLikes(String account,int limit){return new ArrayList<LikeChange>(likes);}
        public void acknowledgeLike(LikeChange change){likes.remove(change);}
        public List<ListenEvent> pendingListens(String account,int limit){return new ArrayList<ListenEvent>(listens);}
        public void acknowledgeListens(List<ListenEvent> batch){listens.removeAll(batch);}
        public void applyStarredSnapshot(String account,List<Song> songs){snapshots++;}
    }
    private static final class Source implements BacksyncEngine.Source {
        boolean failLike,failListen,failStarred,canceled;int submissions,sent,stars;Store store;
        public void guard()throws IOException{if(canceled)throw new IOException("Canceled");}
        public void like(String id,boolean liked)throws IOException{stars++;if(failLike)throw new IOException("Like failed");}
        public void scrobble(List<ListenEvent> batch)throws IOException{check(batch.size()<=25,"Bounded scrobble batch");submissions++;if(failListen && submissions==2)throw new IOException("Listen failed");sent+=batch.size();}
        public List<Song> starred()throws IOException{if(failStarred)throw new IOException("Incomplete snapshot");return Collections.emptyList();}
    }
    private static Store store(int count){Store store=new Store();store.likes.add(new LikeChange("account","s",true,1));for(int n=0;n<count;n++)store.listens.add(new ListenEvent("session"+n,"account","s",1000+n));return store;}
    private static void backsync()throws Exception{
        BacksyncEngine engine=new BacksyncEngine();Store store=store(60);Source source=new Source();BacksyncEngine.Result result=engine.check(store,"account",source);
        check(result.success() && result.listens==60 && result.likes==1 && source.submissions==3,"All feedback sent in batches");check(store.likes.isEmpty() && store.listens.isEmpty() && store.snapshots==1,"Confirmed feedback removed");
        source.submissions=0;engine.check(store,"account",source);check(source.submissions==0 && source.stars==1,"Confirmed events not sent twice");
        store=store(60);source=new Source();source.failLike=true;source.failListen=true;result=engine.check(store,"account",source);
        check(result.likesFailed && result.listensFailed && store.likes.size()==1 && store.listens.size()==35,"Failed requests kept, confirmed batch removed");check(store.snapshots==1,"Upload errors do not block remote likes");
        source.failLike=false;source.failListen=false;result=engine.check(store,"account",source);check(result.success() && result.listens==35 && store.listens.isEmpty(),"Retry sends only unconfirmed listens");
        source.failStarred=true;result=engine.check(store,"account",source);check(result.starredFailed && store.snapshots==2,"Failed starred fetch preserves snapshot");
        store=store(1);source=new Source();source.canceled=true;try{engine.check(store,"account",source);throw new AssertionError("Guard ignored");}catch(IOException expected){checks++;}
        check(store.likes.size()==1 && store.listens.size()==1 && store.snapshots==0,"Account/network guard leaves outbox intact");
    }
}
