package io.onloopio.player;

/** Counts audio progress, excluding seeks, pauses and stalled decoding. No Android clock dependency. */
public final class ListeningSession {
    private long played,lastAt;
    private int lastPosition;
    private boolean sampled,recorded; private int policy=2;
    private long clockWall,clockElapsed;private boolean clockSampled,clockUncertain;
    public void reset(){restore(0,false,2);clockWall=clockElapsed=0;clockSampled=clockUncertain=false;}
    public void restore(long progress,boolean qualified,int version){played=Math.max(0,progress);recorded=qualified;policy=version;sampled=false;}
    public int policyVersion(){return policy;}
    public boolean qualified(){return recorded;}
    /** A discontinuity marks the original wall timestamp; it never fabricates a replacement. */
    public void observeClock(long wall,long elapsed){
        if(wall<946684800000L || elapsed<0 || clockSampled && (elapsed<clockElapsed || Math.abs((wall-clockWall)-(elapsed-clockElapsed))>120000))clockUncertain=true;
        clockWall=wall;clockElapsed=elapsed;clockSampled=true;
    }
    public void restoreClock(long wall,long elapsed,boolean uncertain){clockWall=wall;clockElapsed=elapsed;clockSampled=wall!=0;clockUncertain=uncertain;}
    public long clockWall(){return clockWall;}
    public long clockElapsed(){return clockElapsed;}
    public boolean clockUncertain(){return clockUncertain || !clockSampled;}
    public static long threshold(int duration,int version){return version==1?(duration>0?Math.min(30000,Math.max(1000,duration/2)):30000):duration>0?(duration<=30000?Long.MAX_VALUE:Math.min(duration/2,240000)):240000;}
    public void baseline(long now,int position){lastAt=now;lastPosition=position;sampled=true;}
    public boolean sample(long now,int position,int duration,boolean advancing) {
        if(sampled && advancing) {
            long elapsed=Math.max(0,now-lastAt),progress=(long)position-lastPosition;
            if(progress>0)played+=Math.min(progress,elapsed);
        }
        baseline(now,position);
        long threshold=threshold(duration,policy);
        if(!recorded && played>=threshold){recorded=true;return true;}
        return false;
    }
    public long playedMillis(){return played;}
}
