package io.onloopio.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import io.onloopio.db.MetadataStore;
import io.onloopio.player.PlaybackService;
import java.util.ArrayList;
import java.util.List;

/** Live per-track queue/history with wheel-operated retry/cancel actions. */
public final class DownloadsActivity extends WheelActivity {
    private int page;private MetadataStore store;private final Handler handler=new Handler();
    public void onCreate(Bundle state){store=new MetadataStore(this);super.onCreate(state);}
    protected void onResume(){super.onResume();handler.postDelayed(update,1000);}
    protected void onPause(){handler.removeCallbacks(update);super.onPause();}
    protected void onDestroy(){store.close();super.onDestroy();}
    private final Runnable update=new Runnable(){public void run(){if(stack.size()==1){remember();render();}handler.postDelayed(this,1000);}};
    Menu rootMenu(){return new Menu("Download queue"){List<Item> items(){
        final PlaybackService.DownloadState active=PlaybackService.downloads;int done=store.downloadCount(2),failed=store.downloadCount(1),waiting=store.downloadCount(0),count=done+failed+waiting;page=Math.min(page,Math.max(0,(count-1)/100));List<MetadataStore.Download> jobs=store.downloads(page*100,100);
        boolean paused=prefs.flag("downloads_paused",false);note=(paused?"Paused":prefs.flag("force_offline",false)?"Forced offline":!new io.onloopio.device.OnlineMode(DownloadsActivity.this).homeWifi()?"Waiting for home Wi-Fi":active.phase)+" · "+waiting+" waiting · "+failed+" failed · "+done+" saved";
        if(active.song!=null)note+="\n"+active.song.title+" · "+progress(active.received,active.total)+" · "+size(active.speed)+"/s";
        List<Item> rows=new ArrayList<Item>();
        rows.add(new Item(paused?"Resume / retry failed":"Pause downloads",new Runnable(){public void run(){PlaybackService.action(DownloadsActivity.this,prefs.flag("downloads_paused",false)?PlaybackService.RETRY:PlaybackService.CANCEL);}}));
        rows.add(new Item("Resume / retry failed",new Runnable(){public void run(){PlaybackService.action(DownloadsActivity.this,PlaybackService.RETRY);}}));
        rows.add(new Item("Clear download queue",new Runnable(){public void run(){confirm("Clear queued downloads? Saved audio stays.",new Runnable(){public void run(){PlaybackService.action(DownloadsActivity.this,PlaybackService.CLEAR_QUEUE);}});}}));
        if(jobs.isEmpty())rows.add(new Item("No downloads yet",new Runnable(){public void run(){}}));
        if(active.song!=null)for(MetadataStore.Download job:jobs)if(job.song.id.equals(active.song.id))rows.add(jobRow(job,active));
        for(MetadataStore.Download job:jobs)if(active.song==null || !job.song.id.equals(active.song.id))rows.add(jobRow(job,active));
        if(page>0)rows.add(new Item("Previous page",new Runnable(){public void run(){page--;render();}}));if((page+1)*100<count)rows.add(new Item("Next page",new Runnable(){public void run(){page++;render();}}));
        rows.add(new Item("Back",new Runnable(){public void run(){finish();}}));return rows;
    }};}
    private Item jobRow(final MetadataStore.Download job,PlaybackService.DownloadState active){
        boolean current=active.song!=null && job.song.id.equals(active.song.id);String state=job.state==2?"Saved":job.state==1?"Failed: "+job.error:current?"Downloading · "+progress(active.received,active.total):prefs.flag("downloads_paused",false)?"Paused":"Waiting";
        return new Item(job.song.title+"\n"+state+(job.state==2?" · "+size(job.received):""),new Runnable(){public void run(){show(new Menu(job.song.title){List<Item> items(){List<Item> result=new ArrayList<Item>();note=job.song.artist+" · "+job.song.album+"\n"+job.error;
            if(job.state!=2){result.add(new Item("Retry this track",new Runnable(){public void run(){store.retryDownload(job.song.id);PlaybackService.action(DownloadsActivity.this,PlaybackService.RESUME_PENDING);onBackPressed();}}));result.add(new Item("Remove queued track",new Runnable(){public void run(){startService(new Intent(DownloadsActivity.this,PlaybackService.class).setAction(PlaybackService.REMOVE_DOWNLOAD).putExtra("id",job.song.id));onBackPressed();}}));}
            result.add(new Item("Protect from cleanup",new Runnable(){public void run(){store.pin(java.util.Collections.singletonList(job.song.id),true);onBackPressed();}}));result.add(new Item("Back",new Runnable(){public void run(){onBackPressed();}}));return result;}});}},null,"download:"+job.song.id);
    }
    static String progress(long bytes,long total){return total>0?Math.min(100,bytes*100/total)+"% · "+size(bytes)+" / "+size(total):size(bytes)+" · size unknown";}
    static String size(long bytes){if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return bytes/1024+" KiB";if(bytes<1024L*1024*1024)return String.format(java.util.Locale.US,"%.1f MiB",bytes/(1024.0*1024));return String.format(java.util.Locale.US,"%.1f GiB",bytes/(1024.0*1024*1024));}
}
