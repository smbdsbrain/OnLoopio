package io.onloopio.ui;
import io.onloopio.db.*;
import java.util.*;
/** Held feedback remains durable until retry or an explicit wheel action. */
public final class FeedbackActivity extends WheelActivity {
    private MetadataStore store;
    public void onCreate(android.os.Bundle state){store=new MetadataStore(this);super.onCreate(state);}
    protected void onDestroy(){store.close();super.onDestroy();}
    Menu rootMenu(){return new Menu("Feedback queue"){List<Item> items(){final String account=store.accountKey();List<Item> result=new ArrayList<Item>();
        note=Ui.label(FeedbackActivity.this,"Pending feedback")+": "+store.pendingFeedback(account);
        for(final FeedbackStore.Held held:new FeedbackStore(store).held(account))result.add(new Item(held.kind+" · "+held.item+" · "+Ui.label(FeedbackActivity.this,held.state),new Runnable(){public void run(){choose("Feedback queue",new String[]{"Retry feedback","Discard feedback","Back"},0,new Choice(){public void apply(int n){if(n==0){new FeedbackStore(store).retry(account,held);startService(new android.content.Intent(FeedbackActivity.this,io.onloopio.sync.PlaylistSyncService.class).putExtra("reason","manual").putExtra("backsync_only",true));}else if(n==1)confirm("Discard feedback?",new Runnable(){public void run(){new FeedbackStore(store).deleteHeld(account,held);}});}});}}));
        result.add(new Item("Back",new Runnable(){public void run(){finish();}}));return result;
    }};}
}
