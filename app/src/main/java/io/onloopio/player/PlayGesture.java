package io.onloopio.player;

/** Resolves a complete Play/Pause key sequence before changing playback. */
public final class PlayGesture {
    public static final long GAP_MS=420;
    public static final int NONE=0,SINGLE=1,DOUBLE=2;
    private boolean down,pending;
    private long lastDownTime=Long.MIN_VALUE,lastRelease;
    private String context;
    public void cancel(){down=false;pending=false;context=null;}
    private boolean valid(String current,boolean locked){return !locked && (context==null || context.equals(current));}
    public int key(boolean pressed,boolean canceled,int repeat,long downTime,long now,String current,boolean locked){
        if(!valid(current,locked))cancel();
        if(locked)return NONE;
        if(pressed){
            if(repeat!=0 || down || downTime==lastDownTime)return NONE;
            int result=pending && now-lastRelease>=GAP_MS?SINGLE:NONE;
            if(result==SINGLE)pending=false;
            lastDownTime=downTime;down=true;context=current;return result;
        }
        if(!down)return NONE;
        down=false;
        if(canceled){cancel();return NONE;}
        if(pending && now-lastRelease<GAP_MS){pending=false;context=null;return DOUBLE;}
        int result=pending?SINGLE:NONE;lastRelease=now;pending=true;return result;
    }
    public int finish(long now,String current,boolean locked){
        if(!valid(current,locked)){cancel();return NONE;}
        if(pending && !down && now-lastRelease>=GAP_MS){pending=false;context=null;return SINGLE;}
        return NONE;
    }
    public long remaining(long now){return pending && !down?Math.max(1,GAP_MS-(now-lastRelease)):-1;}
}
