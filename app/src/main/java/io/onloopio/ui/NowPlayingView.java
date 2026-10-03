package io.onloopio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import io.onloopio.model.Song;
import io.onloopio.player.PlaybackService;

/** Fixed Y1 composition scales to the viewport; no duplicate transport list. */
public final class NowPlayingView extends View {
    private PlaybackService.State state=PlaybackService.state;private Bitmap cover,nextCover;
    private boolean locked,wheelSeeking,wheelArmed;private int wheelProgress,volume,maxVolume=1;
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    public NowPlayingView(Context c){super(c);Ui.palette(c);setFocusable(true);setContentDescription("Now Playing");}
    public void update(PlaybackService.State state){this.state=state;describe();invalidate();}
    public void controls(boolean locked,boolean seeking,boolean armed,int progress,int volume,int maxVolume){
        this.locked=locked;wheelSeeking=seeking;wheelArmed=armed;wheelProgress=progress;this.maxVolume=Math.max(1,maxVolume);this.volume=Math.max(0,Math.min(volume,this.maxVolume));
        describe();invalidate();
    }
    private void describe(){setContentDescription("Now Playing · "+(wheelSeeking?"Seek":"Volume")+" · "+volume+"/"+maxVolume+" · "+(locked?"Controls locked · 4 center taps to unlock":wheelArmed?"Wheel enabled":"Full turn to enable · "+wheelProgress+"%")+" · "+Ui.label(getContext(),"Double Play: like")+(state.liked?" · ♥":""));}
    public void covers(Bitmap current,Bitmap next){cover=current;nextCover=next;invalidate();}
    public boolean hasCover(){return cover!=null;}public boolean hasNextCover(){return nextCover!=null;}
    private void line(Canvas c,String value,float x,float y,int size,int color,float width,boolean bold){text.setTextSize(size);text.setColor(color);text.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(TextUtils.ellipsize(value==null?"":value,text,width,TextUtils.TruncateAt.END).toString(),x,y,text);}
    private void art(Canvas c,Bitmap bitmap,int x,int y,int size){
        RectF area=new RectF(x,y,x+size,y+size);paint.setColor(Ui.SURFACE);c.drawRoundRect(area,8,8,paint);
        if(bitmap!=null && !bitmap.isRecycled()){int edge=Math.min(bitmap.getWidth(),bitmap.getHeight());int left=(bitmap.getWidth()-edge)/2,top=(bitmap.getHeight()-edge)/2;c.drawBitmap(bitmap,new Rect(left,top,left+edge,top+edge),area,paint);}
        else{line(c,"♪",x+size*.32f,y+size*.64f,(int)(size*.42f),Ui.ACCENT,size,false);}
    }
    protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/480f,getHeight()/360f);canvas.drawColor(Ui.BG);
        line(canvas,Ui.label(getContext(),"NOW PLAYING"),16,28,12,Ui.ACCENT,220,true);line(canvas,Ui.deviceInfo(getContext()),340,28,11,Ui.FG,124,false);
        line(canvas,Ui.label(getContext(),locked?"LOCKED":"4×: lock"),238,28,10,Ui.ACCENT,92,true);
        Song song=state.song;art(canvas,cover,16,48,196);
        if(song!=null && state.liked){paint.setColor(Ui.SURFACE);canvas.drawCircle(191,222,15,paint);line(canvas,"♥",180,230,23,Ui.ACCENT,25,true);}
        if(song==null){line(canvas,Ui.label(getContext(),"Choose your music"),230,82,21,Ui.FG,234,true);line(canvas,Ui.label(getContext(),"Hold center for library"),230,112,14,Ui.ACCENT,234,false);}
        else{
            String title=song.title;text.setTextSize(21);text.setTypeface(Typeface.DEFAULT_BOLD);int count=text.breakText(title,true,230,null);
            if(count<title.length()){int space=title.lastIndexOf(' ',count);if(space>0)count=space;}
            line(canvas,title.substring(0,Math.min(title.length(),count)),230,77,21,Ui.FG,234,true);
            if(count<title.length())line(canvas,title.substring(count).trim(),230,103,21,Ui.FG,234,true);
            line(canvas,song.artist,230,133,16,Ui.ACCENT,234,false);line(canvas,song.album,230,155,13,Ui.FG,234,false);
        }
        Song next=state.next;line(canvas,Ui.label(getContext(),"UP NEXT")+(state.queueSize>0?" · "+state.queuePosition+"/"+state.queueSize:""),230,185,10,Ui.ACCENT,234,true);
        art(canvas,nextCover,230,197,48);line(canvas,next==null?Ui.label(getContext(),"End of queue"):next.title,290,214,14,Ui.FG,174,true);line(canvas,next==null?"":next.artist,290,235,12,Ui.FG,174,false);
        line(canvas,locked?Ui.label(getContext(),"Controls locked · 4× center to unlock"):(state.playing?"▶ ":"Ⅱ ")+state.message,16,273,12,Ui.FG,448,false);
        paint.setColor(Ui.TRACK);canvas.drawRoundRect(new RectF(16,290,464,296),3,3,paint);paint.setColor(Ui.ACCENT);
        float ratio=state.duration>0?Math.min(1f,Math.max(0f,state.position/(float)state.duration)):0f;if(ratio>0)canvas.drawRoundRect(new RectF(16,290,16+448*ratio,296),3,3,paint);
        line(canvas,time(state.position),16,316,12,Ui.FG,100,false);line(canvas,time(state.duration),419,316,12,Ui.FG,45,false);
        String mode=Ui.label(getContext(),wheelSeeking?"Seek":"Volume")+" · "+(wheelSeeking?"±5 s · ":"")+volume+"/"+maxVolume;
        line(canvas,mode,130,316,12,Ui.ACCENT,272,true);
        paint.setColor(Ui.TRACK);canvas.drawRoundRect(new RectF(130,322,350,326),2,2,paint);paint.setColor(Ui.ACCENT);if(volume>0)canvas.drawRoundRect(new RectF(130,322,130+220*volume/(float)maxVolume,326),2,2,paint);
        String hint=locked?Ui.label(getContext(),"4× center: unlock · Power / volume available"):
                wheelArmed?Ui.label(getContext(),wheelSeeking?"Seek enabled · Center: volume · Hold: menu":"Volume enabled · Center: seek · Hold: menu"):
                wheelProgress>0?Ui.label(getContext(),wheelSeeking?"Keep turning to enable seek":"Keep turning to enable volume")+" · "+wheelProgress+"%":
                Ui.label(getContext(),wheelSeeking?"Full turn: seek · Center: volume · Hold: menu":"Full turn: volume · Center: seek · Hold: menu");
        line(canvas,hint,16,347,11,Ui.ACCENT,448,false);canvas.restore();
    }
    private static String time(int ms){return ms/60000+":"+String.format(java.util.Locale.US,"%02d",ms/1000%60);}
}
