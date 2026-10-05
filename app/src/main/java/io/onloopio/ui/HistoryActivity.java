package io.onloopio.ui;
import io.onloopio.db.*;
import java.util.*;
public final class HistoryActivity extends WheelActivity {
    private MetadataStore store;private int offset;
    public void onCreate(android.os.Bundle state){store=new MetadataStore(this);super.onCreate(state);}
    protected void onDestroy(){store.close();super.onDestroy();}
    Menu rootMenu(){return new Menu("Listening history"){List<Item> items(){List<Item> result=new ArrayList<Item>();List<FeedbackStore.Row> rows=new FeedbackStore(store).history(store.accountKey(),offset);for(final FeedbackStore.Row r:rows)result.add(new Item(r.title+" · "+r.artist,new Runnable(){public void run(){note=r.played/1000+" s · "+Ui.label(HistoryActivity.this,r.outcome)+(r.clockUncertain?" · "+Ui.label(HistoryActivity.this,"Event time uncertain"):"");}}));if(rows.size()==100)result.add(new Item("Next page",new Runnable(){public void run(){offset+=100;stack.get(0).selected=0;stack.get(0).selectedRow=null;render();}}));if(offset>0)result.add(new Item("Previous page",new Runnable(){public void run(){offset=Math.max(0,offset-100);stack.get(0).selected=0;stack.get(0).selectedRow=null;render();}}));result.add(new Item("Back",new Runnable(){public void run(){finish();}}));return result;}};}
}
