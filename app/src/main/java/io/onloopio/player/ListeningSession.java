package io.onloopio.player;

/** Counts audio progress, excluding seeks, pauses and stalled decoding. No Android clock dependency. */
public final class ListeningSession {
    private long played,lastAt;
    private int lastPosition;
    private boolean sampled,recorded;
    public void reset(){played=0;sampled=false;recorded=false;}
    public void baseline(long now,int position){lastAt=now;lastPosition=position;sampled=true;}
    public boolean sample(long now,int position,int duration,boolean advancing) {
        if(sampled && advancing) {
            long elapsed=Math.max(0,now-lastAt),progress=(long)position-lastPosition;
            if(progress>0)played+=Math.min(progress,elapsed);
        }
        baseline(now,position);
        long threshold=duration>0?Math.min(30000,Math.max(1000,duration/2)):30000;
        if(!recorded && played>=threshold){recorded=true;return true;}
        return false;
    }
    public long playedMillis(){return played;}
}
