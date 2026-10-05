package io.onloopio.player;
import io.onloopio.model.CacheKey;
/** 0 retains legacy compatible behavior. Variants never overwrite each other's artifact. */
public final class AudioProfile {
    public static final int COMPATIBLE=0,ORIGINAL=1,COMPACT=2;
    public static String specification(int profile){return profile==ORIGINAL?"original-v1":profile==COMPACT?"mp3-192-v1":"compatible-v1";}
    public static String token(String song,int profile){return profile==COMPATIBLE?CacheKey.audioName(song):CacheKey.hash(song+"\u0000"+specification(profile))+".audio";}
}
