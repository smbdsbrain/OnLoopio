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
        final int minutes=prefs.number("sync_interval_minutes",SyncScheduler.DEFAULT_MINUTES);
        rows.add(new Item("Check every: "+(minutes==0?"Off":minutes+" min"),new Runnable(){public void run(){String[] labels=new String[MINUTES.length];int selected=0;for(int n=0;n<MINUTES.length;n++){labels[n]=MINUTES[n]==0?"Off":MINUTES[n]+" min";if(MINUTES[n]==minutes)selected=n;}choose("Check every",labels,selected,new Choice(){public void apply(int n){prefs.setNumber("sync_interval_minutes",MINUTES[n]);changed();}});}}));
        rows.add(toggle("Check on charger connection","sync_on_charge"));rows.add(toggle("Check on charger removal","sync_on_unplug"));rows.add(toggle("Check on home Wi-Fi connection","sync_on_wifi"));
        rows.add(new Item("Offline playlists",new Runnable(){public void run(){offlinePlaylists();}}));
        rows.add(new Item("Check now",new Runnable(){public void run(){PlaylistSyncService.request(SyncSettingsActivity.this,"manual",false,null);}}));
        rows.add(new Item("Back",new Runnable(){public void run(){finish();}}));return rows;
    }};}
    private void offlinePlaylists(){show(new Menu("Offline playlists"){List<Item> items(){List<Item> rows=new ArrayList<Item>();note="Keep current members offline; new songs join the download queue.";
        for(final Playlist p:store.playlists())rows.add(new Item((store.followsPlaylist(p.id)?"✓ ":"")+p.name,new Runnable(){public void run(){choose(p.name,new String[]{"Keep playlist offline","Stop following updates"},store.followsPlaylist(p.id)?0:1,new Choice(){public void apply(int n){store.followPlaylist(p.id,n==0);if(n==0)PlaylistSyncService.request(SyncSettingsActivity.this,"manual",false,p.id);}});}}));
        rows.add(new Item("Back",new Runnable(){public void run(){onBackPressed();}}));return rows;
    }});}
}
