package io.onloopio.player;

import io.onloopio.db.PartialStore;
import java.io.File;
import java.io.IOException;
import java.util.Set;

/** Expired private partials restart safely; completed audio and publishing files are excluded. */
public final class PartialCleanup {
    public static final long MAX_IDLE_MS=7L*86400000;
    public static boolean expired(long now,long last){return now>=946684800000L && last>0 && last<=now && now-last>MAX_IDLE_MS;}
    /** Caller holds AudioFileIndex.IO and has excluded active transfers. */
    public static int sweep(File directory,String account,PartialStore journal,Set<String> publishing,long now)throws IOException{
        int removed=0;File[] files=directory.listFiles();String base=directory.getCanonicalPath();
        if(files!=null)for(File file:files){String name=file.getName();
            if(!name.matches("[0-9a-f]{64}\\.audio\\.part") || !file.isFile() || !base.equals(file.getCanonicalFile().getParent()))continue;
            String token=name.substring(0,name.length()-5);if(publishing.contains(token))continue;
            long last=Math.max(file.lastModified(),journal.updated(account,token));
            if(expired(now,last) && file.delete()){journal.clear(account,token);removed++;}
        }
        for(String token:journal.tokens(account))if(token.matches("[0-9a-f]{64}\\.audio") && !publishing.contains(token) && !new File(directory,token+".part").exists())journal.clear(account,token);
        return removed;
    }
}
