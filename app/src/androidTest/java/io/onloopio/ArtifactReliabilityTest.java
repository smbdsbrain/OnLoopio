package io.onloopio;

import android.test.InstrumentationTestCase;
import android.test.RenamingDelegatingContext;
import io.onloopio.db.*;
import io.onloopio.player.*;
import io.onloopio.model.*;
import java.io.*;
import java.util.*;

public final class ArtifactReliabilityTest extends InstrumentationTestCase {
    private MetadataStore store;private File root;private String prefix;
    protected void setUp()throws Exception{super.setUp();prefix="artifact_"+System.nanoTime()+"_";store=new MetadataStore(new RenamingDelegatingContext(getInstrumentation().getTargetContext(),prefix));root=new File(getInstrumentation().getTargetContext().getFilesDir(),prefix);assertTrue(root.mkdir());}
    protected void tearDown()throws Exception{store.close();getInstrumentation().getTargetContext().deleteDatabase(prefix+"onloopio.db");for(File f:root.listFiles())assertTrue(f.delete());assertTrue(root.delete());super.tearDown();}
    private File file(String token,long modified)throws Exception{File f=new File(root,token+".part");FileOutputStream out=new FileOutputStream(f);out.write(new byte[64]);out.close();assertTrue(f.setLastModified(modified));return f;}
    public void testPartialAgingPreservesRecentPublishingAndOtherFiles()throws Exception{
        long now=System.currentTimeMillis(),old=now-8L*86400000;PartialStore journal=new PartialStore(store);String stale=CacheKey.audioName("stale"),recent=CacheKey.audioName("recent"),publishing=CacheKey.audioName("publishing"),orphan=CacheKey.audioName("orphan"),missing=CacheKey.audioName("missing");
        File a=file(stale,old),b=file(recent,old),c=file(publishing,old),d=file(orphan,old),foreign=file("user.wav",old);io.onloopio.api.ResumableDownload.State state=new io.onloopio.api.ResumableDownload.State();state.validator="\"fixture\"";state.offset=64;
        for(String token:Arrays.asList(stale,recent,publishing,missing))journal.save("fixture",token,state);
        store.getWritableDatabase().execSQL("UPDATE audio_partial SET updated_at=? WHERE token!=?",new Object[]{old,recent});
        assertEquals(2,PartialCleanup.sweep(root,"fixture",journal,new HashSet<String>(Arrays.asList(publishing)),now));assertFalse(a.exists());assertFalse(d.exists());assertTrue(b.exists());assertTrue(c.exists());assertTrue(foreign.exists());assertEquals(0,journal.load("fixture",stale).offset);assertEquals(0,journal.load("fixture",missing).offset);assertEquals(64,journal.load("fixture",publishing).offset);assertEquals(0,PartialCleanup.sweep(root,"fixture",journal,Collections.<String>emptySet(),old-1000));
    }
    public void testActualMp3BitrateAndArtifactPersistence()throws Exception{
        File f=new File(root,"fixture.mp3");InputStream input=getInstrumentation().getContext().getAssets().open("music/fixture.mp3");FileOutputStream out=new FileOutputStream(f);try{byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)out.write(b,0,n);}finally{input.close();out.close();}
        AudioFacts facts=AudioFacts.read(f);assertEquals("mp3",facts.format);assertTrue(facts.durationMs>1000);assertTrue(facts.bitrate>=32000 && facts.bitrate<=33000);Song song=new Song("A","Fixture","Synthetic","Album","flac",0);new ArtifactStore(store).record("fixture",song,AudioProfile.COMPACT,f);store.close();RenamingDelegatingContext reopened=new RenamingDelegatingContext(getInstrumentation().getTargetContext(),prefix);reopened.makeExistingFilesAndDbsAccessible();store=new MetadataStore(reopened);ArtifactStore.Row row=new ArtifactStore(store).rows("fixture","A").get(0);assertEquals("mp3",row.actual);assertEquals(f.length(),row.bytes);assertEquals(facts.bitrate,row.bitrate);assertEquals(facts.durationMs,row.durationMs);android.os.Bundle result=new android.os.Bundle();result.putString("stream","Measured MP3 file_average_bitrate="+facts.bitrate+" vendor_reported="+facts.reportedBitrate+" duration_ms="+facts.durationMs+" bytes="+row.bytes+"\n");getInstrumentation().sendStatus(0,result);
    }
}
