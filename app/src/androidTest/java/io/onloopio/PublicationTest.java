package io.onloopio;
import android.test.InstrumentationTestCase;
import io.onloopio.library.AudioFileIndex;
import java.io.*;
/** Deterministic process-crash states at the journal/registry/rename boundaries. */
public final class PublicationTest extends InstrumentationTestCase {
    public void testJournalRecoveryAtEveryPublicationBoundary()throws Exception{
        android.content.Context c=getInstrumentation().getTargetContext();File root=new File(c.getFilesDir(),"publication-fixture"),staging=new File(root,"staging");staging.mkdirs();AudioFileIndex index=new AudioFileIndex(c);
        try{for(int point=0;point<4;point++){String account="publication-"+point,token="token-"+point;File part=new File(staging,token+".part"),target=new File(root,token+".wav");FileOutputStream out=new FileOutputStream(part);try{out.write("RIFF0123456789abcdef".getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
            if(point>=1)index.beginPublication(account,token,part,target);if(point>=2)index.record(account,token,target.getCanonicalPath(),part.length(),part.lastModified());if(point>=3)assertTrue(part.renameTo(target));
            index.recover(account,root,staging);index.recover(account,root,staging);if(point==0){assertFalse(target.exists());assertTrue(part.exists());}else {assertTrue(target.isFile());assertTrue(index.entries(account).get(0).matches(target));assertFalse(part.exists());}index.forget(account,token);part.delete();target.delete();
        }}finally{index.close();staging.delete();root.delete();}
    }
}
