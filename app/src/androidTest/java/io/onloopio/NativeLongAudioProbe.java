package io.onloopio;

import android.app.Instrumentation;
import android.os.Bundle;
import android.media.MediaPlayer;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Framework-only control: distinguishes vendor long-file seek behavior from app lifecycle. */
public final class NativeLongAudioProbe extends Instrumentation {
    private String format;
    public void onCreate(Bundle args){super.onCreate(args);format=args.getString("format","flac");start();}
    public void onStart(){Bundle result=new Bundle();final MediaPlayer[] player={null};StringBuilder report=new StringBuilder();int code=-1;
        try{if(!format.matches("mp3|flac"))throw new IllegalArgumentException("Explicit long format");final File file=new File("/data/local/tmp/onloopio-long-fixture","mp3".equals(format)?"long-vbr.mp3":"long.flac");if(file.length()<16 || file.length()>256L*1024*1024)throw new IllegalStateException("Bounded synthetic fixture required");
            runOnMainSync(new Runnable(){public void run(){try{player[0]=new MediaPlayer();player[0].setDataSource(file.getPath());player[0].setVolume(0,0);player[0].prepare();player[0].start();}catch(Exception e){throw new RuntimeException(e);}}});
            report.append("native duration_ms=").append(player[0].getDuration()).append('\n');
            for(final int position:new int[]{3600000,6900000}){final CountDownLatch sought=new CountDownLatch(1);runOnMainSync(new Runnable(){public void run(){player[0].pause();player[0].setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener(){public void onSeekComplete(MediaPlayer p){sought.countDown();}});player[0].seekTo(position);}});if(!sought.await(20,TimeUnit.SECONDS))throw new AssertionError("Native seek callback timeout");runOnMainSync(new Runnable(){public void run(){player[0].start();}});Thread.sleep(2000);int actual=player[0].getCurrentPosition();report.append("requested_ms=").append(position).append(" after_resume_ms=").append(actual).append('\n');if(Math.abs(actual-position-2000)>4000)throw new AssertionError("Native long seek position");}
            report.append("Native long ").append(format).append(" PASS\n");
        }catch(Throwable failure){code=0;report.append(android.util.Log.getStackTraceString(failure));}
        finally{runOnMainSync(new Runnable(){public void run(){if(player[0]!=null)player[0].release();}});}result.putString("stream",report.toString());finish(code,result);
    }
}
