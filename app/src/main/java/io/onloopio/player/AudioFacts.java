package io.onloopio.player;

import android.media.MediaMetadataRetriever;
import java.io.File;
import java.io.IOException;

/** Measure completed artifacts on a background worker, never while rendering the UI. */
public final class AudioFacts {
    public final String format;
    public final long durationMs,bitrate,reportedBitrate;
    private AudioFacts(String f,long d,long b,long reported){format=f;durationMs=d;bitrate=b;reportedBitrate=reported;}
    public static AudioFacts read(File file)throws IOException{
        String format=AudioCache.audioExtension(file,"");long duration=0,rate=0;
        MediaMetadataRetriever reader=new MediaMetadataRetriever();
        try{reader.setDataSource(file.getCanonicalPath());duration=number(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));rate=number(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));}
        catch(RuntimeException unsupported){/* Unknown is preserved, never replaced by the request. */}
        finally{reader.release();}
        // Vendor metadata can report the first VBR frame's rate. File average includes headers.
        long average=duration>0?Math.round(file.length()*8000.0/duration):0;
        return new AudioFacts(format,duration,average,rate<=100000000?rate:0);
    }
    private static long number(String value){try{long n=Long.parseLong(value);return n>0?n:0;}catch(Exception absent){return 0;}}
}
