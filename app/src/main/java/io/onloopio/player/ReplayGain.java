package io.onloopio.player;
/** API17 relative gain with common 6 dB headroom; system volume is never changed. */
public final class ReplayGain {
    public static final double HEADROOM_DB=6;
    public final Double track,album,trackPeak,albumPeak,base,fallback;
    public ReplayGain(Double t,Double a,Double tp,Double ap,Double b,Double f){track=finite(t);album=finite(a);trackPeak=peak(tp);albumPeak=peak(ap);base=finite(b);fallback=finite(f);}
    public static Double finite(Double v){return v!=null && !v.isNaN() && !v.isInfinite() && Math.abs(v)<1000?v:null;}
    private static Double peak(Double v){v=finite(v);return v!=null && v>0?v:null;}
    public float volume(int mode,double preamp,double eqBoost,boolean alreadyNormalized){
        if(mode!=1 && mode!=2)return 1;
        preamp=finite(preamp)==null?0:preamp;
        eqBoost=finite(eqBoost)==null?120:Math.max(0,eqBoost);
        // A normalized decoder/transcoder skips metadata gain, but still needs EQ headroom.
        if(alreadyNormalized)return (float)Math.pow(10,(-HEADROOM_DB-eqBoost)/20);
        Double db=mode==2 && album!=null?album:track;Double p=mode==2 && album!=null?albumPeak:trackPeak;
        if(db==null)db=fallback;if(db==null)db=0.0;
        double scalar=Math.pow(10,(db+preamp-HEADROOM_DB-eqBoost)/20);
        if(p!=null)scalar=Math.min(scalar,1/(p*Math.pow(10,eqBoost/20)));
        return (float)Math.max(0,Math.min(1,scalar));
    }
}
