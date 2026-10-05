package io.onloopio.player;

import android.content.Context;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.DeviceSettings;
import io.onloopio.model.Song;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Only this account's audio is eligible. Pins/current playback/downloads are protected. */
public final class CachePolicy {
    public static final long MIB=1024L*1024;
    public static final int DEFAULT_LIMIT_MB=96*1024,DEFAULT_RESERVE_MB=2*1024;
    private final DeviceSettings settings;private final MetadataStore store;private final AudioCache cache;
    public CachePolicy(Context c,MetadataStore store,AudioCache cache){this.settings=new DeviceSettings(c);this.store=store;this.cache=cache;}
    public static final class Result {public int removed;public long freed;public boolean blocked;}
    private static final class Candidate {final Song song;final File file;final String token;final long used;Candidate(Song s,AudioCache.Artifact f,long u){song=s;file=f.file;token=f.token;used=u;}}
    public long limit(){int mb=settings.number("cache_limit_mb",DEFAULT_LIMIT_MB);return mb<=0?Long.MAX_VALUE:mb*MIB;}
    public long reserve(){return Math.max(16,settings.number("cache_reserve_mb",DEFAULT_RESERVE_MB))*MIB;}
    public Result clean(Set<String> playback,long incoming,long now,boolean manual)throws IOException {
        return run(playback,incoming,incoming,now,manual,true);
    }
    public Result preview(Set<String> playback,long now)throws IOException{return run(playback,0,0,now,true,false);}
    private Result run(Set<String> playback,long incoming,long diskNeeded,long now,boolean manual,boolean execute)throws IOException {
        Result result=new Result();long bytes=cache.completedBytes()+cache.partialBytes(),free=cache.freeBytes();boolean automatic=settings.flag("cache_auto_clean",false);
        if(!automatic && !manual){result.blocked=bytes>limit() || incoming>limit()-bytes || free-diskNeeded<reserve();return result;}
        Set<String> queued=store.queuedIds();List<Candidate> candidates=new ArrayList<Candidate>();
        int days=settings.number("cache_max_idle_days",90);long cutoff=days>0?now-days*86400000L:Long.MIN_VALUE;
        for(Song song:store.offlineSongs(cache.completedNames())){
            MetadataStore.AudioState state=store.audioState(song.id);
            if(store.protectedFromCleanup(song.id) || playback.contains(song.id) || queued.contains(song.id))continue;
            for(AudioCache.Artifact artifact:cache.artifacts(song.id)){long downloaded=state.downloaded>0?state.downloaded:artifact.file.lastModified();candidates.add(new Candidate(song,artifact,Math.max(state.lastPlayed,downloaded)));}
        }
        Collections.sort(candidates,new Comparator<Candidate>(){public int compare(Candidate a,Candidate b){return a.used<b.used?-1:a.used==b.used?a.song.id.compareTo(b.song.id):1;}});
        for(Candidate c:candidates){
            if(PlaybackService.protectedTracks.contains(c.song.id) || store.protectedFromCleanup(c.song.id))continue;
            if(c.used>=cutoff && bytes<=limit() && incoming<=limit()-bytes && free-diskNeeded>=reserve())continue;
            long size=c.file.length();if(!execute || cache.removeArtifact(c.token)){bytes-=size;free+=size;result.freed+=size;result.removed++;}
        }
        result.blocked=bytes>limit() || incoming>limit()-bytes || free-diskNeeded<reserve();return result;
    }
    public void requireSpace(Set<String> playback,long incoming,long received)throws IOException {long remaining=Math.max(0,incoming-received),unaccounted=Math.max(0,received-cache.partialBytes());Result result=run(playback,remaining+unaccounted,remaining,System.currentTimeMillis(),false,true);if(result.blocked)throw new IOException("Cache limit / protected tracks / SD reserve");}
}
