package io.onloopio.ui;

import android.os.Bundle;
import android.os.Handler;
import io.onloopio.db.MetadataStore;
import io.onloopio.model.Playlist;
import io.onloopio.sync.PlaylistSyncService;
import io.onloopio.sync.SyncScheduler;
import java.util.ArrayList;
import java.util.List;

/** Physical wheel configuration; no keyboard or Android settings required. */
public final class SyncSettingsActivity extends WheelActivity {
    private final Handler main=new Handler();private MetadataStore store;
    private static final int[] MINUTES={0,5,15,30,60,120,360};
    public void onCreate(Bundle state){store=new MetadataStore(this);super.onCreate(state);}
    protected void onResume(){super.onResume();main.postDelayed(update,1000);}
    protected void onPause(){main.removeCallbacks(update);super.onPause();}
    protected void onDestroy(){store.close();super.onDestroy();}
    private final Runnable update=new Runnable(){public void run(){if(stack.size()==1){remember();render();}main.postDelayed(this,1000);}};
    private void changed(){SyncScheduler.ensure(this,true);}
    private Item toggle(final String label,final String key){return new Item(label+": "+(prefs.flag(key,true)?"On":"Off"),new Runnable(){public void run(){choose(label,new String[]{"Off","On"},prefs.flag(key,true)?1:0,new Choice(){public void apply(int n){prefs.setFlag(key,n==1);changed();}});}});}
    Menu rootMenu(){return new Menu("Playlist synchronization"){List<Item> items(){
        long checked=store.lastPlaylistCheck();note="Last check: "+(checked==0?"Never":new java.text.SimpleDateFormat("dd MMM HH:mm",java.util.Locale.getDefault()).format(new java.util.Date(checked)))+"\n"+prefs.text("sync_status","Waiting for a check")+"\n"+Ui.label(SyncSettingsActivity.this,"Pending feedback")+": "+store.pendingFeedback(store.accountKey());
        List<Item> rows=new ArrayList<Item>();rows.add(toggle("Automatic playlist sync","playlist_auto_sync"));
        rows.add(toggle("Automatic feedback sync","feedback_auto_sync"));
        rows.add(new Item("Send feedback now",new Runnable(){public void run(){new io.onloopio.db.FeedbackStore(store).retry(store.accountKey());startService(new android.content.Intent(SyncSettingsActivity.this,PlaylistSyncService.class).putExtra("reason","manual").putExtra("backsync_only",true));}}));
        rows.add(new Item("Feedback queue",new Runnable(){public void run(){startActivity(new android.content.Intent(SyncSettingsActivity.this,FeedbackActivity.class));}}));
        rows.add(new Item("Energy profile",new Runnable(){public void run(){choose("Energy profile",new String[]{"Update at home","Large downloads while charging"},prefs.number("energy_profile",0),new Choice(){public void apply(int n){prefs.setNumber("energy_profile",n);changed();}});}}));
        final int minutes=prefs.number("sync_interval_minutes",SyncScheduler.DEFAULT_MINUTES);
        rows.add(new Item("Check every: "+(minutes==0?"Off":minutes+" min"),new Runnable(){public void run(){String[] labels=new String[MINUTES.length];int selected=0;for(int n=0;n<MINUTES.length;n++){labels[n]=MINUTES[n]==0?"Off":MINUTES[n]+" min";if(MINUTES[n]==minutes)selected=n;}choose("Check every",labels,selected,new Choice(){public void apply(int n){prefs.setNumber("sync_interval_minutes",MINUTES[n]);changed();}});}}));
        rows.add(toggle("Check on charger connection","sync_on_charge"));rows.add(toggle("Check on charger removal","sync_on_unplug"));rows.add(toggle("Check on home Wi-Fi connection","sync_on_wifi"));
        rows.add(new Item("Offline playlists",new Runnable(){public void run(){offlinePlaylists();}}));
        rows.add(new Item("Check now",new Runnable(){public void run(){PlaylistSyncService.request(SyncSettingsActivity.this,"manual",false,null);}}));
        rows.add(new Item("Back",new Runnable(){public void run(){finish();}}));return rows;
    }};}
    private void offlinePlaylists(){show(new Menu("Offline playlists"){List<Item> items(){List<Item> rows=new ArrayList<Item>();note="Keep current members offline; new songs join the download queue.";
        for(final Playlist p:store.playlists())rows.add(new Item((store.followsPlaylist(p.id)?"✓ ":"")+p.name,new Runnable(){public void run(){choose(p.name+"\n"+OfflineStatus.label(SyncSettingsActivity.this,new io.onloopio.db.GenerationStore(store).summary(store.accountKey(),p.id)),new String[]{"Keep playlist offline","Stop following updates","Retry update","Cancel staging update","Release offline snapshot"},store.followsPlaylist(p.id)?0:1,new Choice(){public void apply(int n){if(n==4){confirm("Release offline snapshot?",new Runnable(){public void run(){new io.onloopio.db.GenerationStore(store).release(store.accountKey(),p.id);}});}else if(n==3){new io.onloopio.db.GenerationStore(store).cancel(store.accountKey(),p.id);store.followPlaylist(p.id,false);}else if(n==2){store.retryDownloads();PlaylistSyncService.request(SyncSettingsActivity.this,"manual",false,p.id);}else {store.followPlaylist(p.id,n==0);if(n==0)PlaylistSyncService.request(SyncSettingsActivity.this,"manual",false,p.id);}}});}}));
        rows.add(new Item("Back",new Runnable(){public void run(){onBackPressed();}}));return rows;
    }});}
}
