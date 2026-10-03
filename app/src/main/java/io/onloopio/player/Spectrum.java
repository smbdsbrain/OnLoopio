package io.onloopio.player;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

/**
 * Session spectrum from the platform Visualizer effect: 8-bit mono FFT, at most 20 captures per second.
 * All Visualizer calls run on their own thread. Android 4.2's libmedia deadlocks inside Visualizer::setEnabled(true)
 * when it follows setEnabled(false) before the capture thread has exited, so disabling is deferred and
 * re-enabling waits out that window; a pause/resume pair never toggles the effect at all.
 */
public final class Spectrum {
    private Spectrum() { }
    public static final int BANDS=32;
    public static final long DISABLE_DELAY_MS=1500,REENABLE_GAP_MS=300;
    private static final float[] EMPTY=new float[BANDS];
    /** Log-spaced band levels 0..1 from the lowest FFT bin to Nyquist; empty while idle. */
    public static volatile float[] bands=EMPTY;
    public static volatile int captures;public static volatile String status="";
    /** The two latest captures and their timing let the view interpolate at display rate. */
    private static volatile float[] current=EMPTY,previous=EMPTY;private static volatile long capturedAt;private static volatile int captureMs=50;
    /** A visible Now Playing screen wants captures; anything else lets the effect idle. */
    public static volatile boolean wanted=true;
    private static final HandlerThread thread=new HandlerThread("OnLoopio spectrum");
    private static final Handler handler;
    static{thread.start();handler=new Handler(thread.getLooper());}
    // Touched on the spectrum thread only.
    private static Visualizer visualizer;private static int session=-1,failed=-1;private static boolean enabled,disablePending;private static long disabledAt;
    private static volatile boolean playing;
    private static final float[] smoothed=new float[BANDS];private static int[] edges;
    /** 8-bit FFT magnitudes are small and device dependent, so levels are relative to a slowly decaying peak. */
    private static final float CEILING_FLOOR=6;private static float ceiling=CEILING_FLOOR;

    /** True while captures are expected and shown. */
    public static boolean active(){return enabled && playing;}
    /** Desired state from the player; cheap and safe from any thread. */
    public static void sync(final int sessionId,final boolean playingNow){
        boolean want=playingNow && wanted && sessionId>0;
        if(playing!=want){playing=want;if(!want)quiet();}
        handler.post(new Runnable(){public void run(){apply(sessionId,want);}});
    }
    public static void release(){handler.post(new Runnable(){public void run(){releaseNow();}});}
    /** Whether the native effect is currently enabled; answered on the spectrum thread so tests can verify idle shutdown. */
    public static boolean effectEnabled(){
        final boolean[] result=new boolean[1];final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        handler.post(new Runnable(){public void run(){try{result[0]=visualizer!=null && enabled && visualizer.getEnabled();}catch(IllegalStateException dead){result[0]=false;}done.countDown();}});
        try{done.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        return result[0];
    }

    private static void apply(int sessionId,boolean want){
        if(sessionId!=session){releaseNow();session=sessionId;}
        if(want){
            if(disablePending){handler.removeCallbacks(disable);disablePending=false;}
            if(visualizer==null && sessionId!=failed){
                try{visualizer=new Visualizer(sessionId);visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);captureMs=Math.max(16,1000000/Math.max(1,Visualizer.getMaxCaptureRate()));
                    visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener(){
                        public void onWaveFormDataCapture(Visualizer v,byte[] waveform,int rate){}
                        public void onFftDataCapture(Visualizer v,byte[] fft,int rate){if(playing)fold(fft);}
                    },Visualizer.getMaxCaptureRate(),false,true);status="";
                }catch(RuntimeException failure){Log.w("OnLoopio","VISUALIZER_UNAVAILABLE",failure);status="Spectrum unavailable";failed=sessionId;releaseNow();session=sessionId;return;}
            }
            if(visualizer!=null && !enabled){
                long wait=disabledAt+REENABLE_GAP_MS-SystemClock.uptimeMillis();
                if(wait>0){final int s=sessionId;handler.postDelayed(new Runnable(){public void run(){if(playing)apply(s,true);}},wait);return;}
                try{visualizer.setEnabled(true);enabled=true;}catch(IllegalStateException dead){releaseNow();}
            }
        } else if(visualizer!=null && enabled && !disablePending){disablePending=true;handler.postDelayed(disable,DISABLE_DELAY_MS);} // scheduled once per idle transition; repeated sync(false) must not push it out
    }
    private static final Runnable disable=new Runnable(){public void run(){
        disablePending=false;if(visualizer==null || !enabled || playing)return;
        try{visualizer.setEnabled(false);}catch(IllegalStateException dead){releaseNow();return;}
        enabled=false;disabledAt=SystemClock.uptimeMillis();
    }};
    private static void releaseNow(){
        handler.removeCallbacks(disable);disablePending=false;
        if(visualizer!=null){if(enabled){try{visualizer.setEnabled(false);}catch(IllegalStateException ignored){}disabledAt=SystemClock.uptimeMillis();}visualizer.release();}
        visualizer=null;enabled=false;session=-1;quiet();
    }
    private static void quiet(){bands=EMPTY;current=EMPTY;previous=EMPTY;synchronized(smoothed){java.util.Arrays.fill(smoothed,0);ceiling=CEILING_FLOOR;}}
    /** fft: re(0), re(n/2), then re/im pairs for bins 1..n/2-1; band peaks are folded into log-spaced bands with a fast attack and slow decay. */
    static void fold(byte[] fft){
        int bins=fft.length/2;if(edges==null || edges[BANDS]!=bins)edges=edges(bins);
        float[] raw=new float[BANDS];float top=0;
        for(int b=0;b<BANDS;b++){float peak=0;for(int k=edges[b];k<edges[b+1];k++){float re=fft[2*k],im=fft[2*k+1];peak=Math.max(peak,re*re+im*im);}raw[b]=(float)Math.sqrt(peak);top=Math.max(top,raw[b]);}
        float[] next=new float[BANDS];
        synchronized(smoothed){
            ceiling=Math.max(CEILING_FLOOR,Math.max(top,ceiling*.995f));
            double floor=Math.log(2),range=Math.log(1+ceiling)-floor;
            for(int b=0;b<BANDS;b++){float level=(float)Math.max(0,(Math.log(1+raw[b])-floor)/range);smoothed[b]=Math.max(level,smoothed[b]*.78f);next[b]=Math.min(1,smoothed[b]);}
        }
        previous=current;current=next;capturedAt=SystemClock.uptimeMillis();bands=next;captures++;
        if(captures%40==0)Log.d("OnLoopio","SPECTRUM captures="+captures+" ceiling="+ceiling+" top="+top+" bands="+java.util.Arrays.toString(next));
    }
    /** Band levels at display time: linear blend from the previous capture to the latest over one capture interval. */
    public static void levels(float[] out,long now){
        float[] from=previous,to=current;float t=Math.max(0,Math.min(1,(now-capturedAt)/(float)captureMs));
        for(int b=0;b<BANDS;b++)out[b]=from[b]+(to[b]-from[b])*t;
    }
    static int[] edges(int bins){int[] e=new int[BANDS+1];e[0]=1;double span=Math.log(bins-1);for(int b=1;b<=BANDS;b++)e[b]=Math.max(e[b-1]+1,(int)Math.round(Math.exp(span*b/BANDS)));e[BANDS]=bins;return e;}
}
