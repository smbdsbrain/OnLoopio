package io.onloopio.sync;

import io.onloopio.db.MetadataStore;
import io.onloopio.model.Library;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Incremental playlist check; no partial network response is published to SQLite. */
public final class PlaylistSyncEngine {
    public static final long AUDIT_INTERVAL=24*60*60*1000L;
    public interface Source {
        void guard()throws IOException;
        List<Playlist> playlists()throws IOException;
        PlaylistDetail detail(String id)throws IOException;
        Library catalog()throws IOException;
    }
    public static final class Result {
        public final int playlists,refreshed,queued;public final boolean catalog,catalogFailed;
        Result(int p,int r,int q,boolean c,boolean failed){playlists=p;refreshed=r;queued=q;catalog=c;catalogFailed=failed;}
    }
    public Result check(MetadataStore store,String account,Source source,List<String> completedNames,long now,boolean full,Set<String> force)throws IOException {
        source.guard();List<Playlist> headers=source.playlists();Map<String,Playlist> old=new HashMap<String,Playlist>();for(Playlist p:store.playlists())old.put(p.id,p);
        boolean audit=full || now-store.lastPlaylistAudit()>=AUDIT_INTERVAL;List<PlaylistDetail> details=new ArrayList<PlaylistDetail>();Set<String> seen=new HashSet<String>();
        for(Playlist p:headers){
            if(p.id.length()==0 || !seen.add(p.id))throw new IOException("Invalid playlist list");
            Playlist previous=old.get(p.id);
            if(audit || force.contains(p.id) || previous==null || !store.hasPlaylistDetail(p.id) || changed(previous,p)){
                source.guard();PlaylistDetail detail=source.detail(p.id);
                if(!p.id.equals(detail.playlist.id) || detail.playlist.songCount!=detail.songs.size())throw new IOException("Incomplete playlist response");
                details.add(detail);
            }
        }
        source.guard();int queued=store.applyPlaylistSync(account,headers,details,null,completedNames,now,audit);
        Library catalog=null;
        boolean catalogFailed=false;if(full){source.guard();try{catalog=source.catalog();}catch(IOException failed){catalogFailed=true;}source.guard();if(!account.equals(store.accountKey()))throw new IOException("Account changed");if(catalog!=null)store.replaceCatalog(catalog);}
        return new Result(headers.size(),details.size(),queued,catalog!=null,catalogFailed);
    }
    private boolean changed(Playlist a,Playlist b){return b.changed.length()==0 || !a.changed.equals(b.changed) || !a.name.equals(b.name) || a.songCount!=b.songCount || a.duration!=b.duration;}
}
