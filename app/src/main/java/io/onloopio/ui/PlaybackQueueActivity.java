package io.onloopio.ui;
import android.content.Intent;
import io.onloopio.player.*;
import java.util.*;
/** Stable occurrence IDs make duplicate tracks independently editable with the wheel. */
public final class PlaybackQueueActivity extends WheelActivity {
    private final android.os.Handler timer=new android.os.Handler();private List<PlaybackQueue.Entry> shown;
    private final Runnable refresh=new Runnable(){public void run(){if(shown!=PlaybackService.playbackQueue && stack.size()==1){remember();render();shown=PlaybackService.playbackQueue;}timer.postDelayed(this,250);}};
    protected void onResume(){super.onResume();if(PlaybackService.state.song==null)PlaybackService.action(this,PlaybackService.KICK);timer.post(refresh);}
    protected void onPause(){timer.removeCallbacks(refresh);super.onPause();}

    private void command(String action,String entry){startService(new Intent(this,PlaybackService.class).setAction(action).putExtra("entry",entry));}
    Menu rootMenu(){return new Menu("Playback queue"){List<Item> items(){List<Item> rows=new ArrayList<Item>();int n=0;for(final PlaybackQueue.Entry e:PlaybackService.playbackQueue){final int at=++n;rows.add(new Item((at==PlaybackService.state.queuePosition?"▶ ":"")+at+". "+e.song.title,new Runnable(){public void run(){choose(e.song.title,new String[]{"Play now","Play next","Remove from queue"},0,new Choice(){public void apply(int value){command(value==0?PlaybackService.QUEUE_JUMP:value==1?PlaybackService.QUEUE_MOVE_NEXT:PlaybackService.QUEUE_REMOVE,e.id);}});}},null,e.id));}rows.add(new Item("Clear remaining",new Runnable(){public void run(){command(PlaybackService.QUEUE_CLEAR_REMAINING,null);}}));rows.add(new Item("Back",new Runnable(){public void run(){finish();}}));return rows;}};}
}
