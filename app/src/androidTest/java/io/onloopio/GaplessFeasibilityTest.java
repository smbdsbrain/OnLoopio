package io.onloopio;
import android.test.InstrumentationTestCase;
import android.media.MediaPlayer;
import java.io.*;
import java.util.concurrent.*;
/** Native two-decoder ownership stress; this does not measure the electrical audio boundary. */
public final class GaplessFeasibilityTest extends InstrumentationTestCase {
    public void testFiftyLocalTransitionsReleaseDecoders()throws Exception{
        File fixture=new File(getInstrumentation().getTargetContext().getFilesDir(),"gapless-silence.wav");DataOutputStream out=new DataOutputStream(new FileOutputStream(fixture));try{int bytes=16000;out.writeBytes("RIFF");le(out,36+bytes,4);out.writeBytes("WAVEfmt ");le(out,16,4);le(out,1,2);le(out,1,2);le(out,16000,4);le(out,32000,4);le(out,2,2);le(out,16,2);out.writeBytes("data");le(out,bytes,4);out.write(new byte[bytes]);}finally{out.close();}
        try{for(int boundary=0;boundary<50;boundary++){
            final CountDownLatch done=new CountDownLatch(1);final MediaPlayer[] players=new MediaPlayer[2];final boolean[] firstDone={false};final String[] error={"none"};
            try{
                getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{
                    MediaPlayer first=new MediaPlayer(),second=new MediaPlayer();players[0]=first;players[1]=second;
                    first.setDataSource(fixture.getPath());second.setDataSource(fixture.getPath());first.setVolume(0,0);second.setVolume(0,0);first.prepare();second.prepare();
                    first.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer player){firstDone[0]=true;}});
                    second.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer player){done.countDown();}});
                    MediaPlayer.OnErrorListener errors=new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer player,int what,int extra){error[0]=what+":"+extra;done.countDown();return true;}};first.setOnErrorListener(errors);second.setOnErrorListener(errors);
                    first.setNextMediaPlayer(second);first.start();
                }catch(Exception failed){throw new IllegalStateException(failed);}}});
                boolean finished=done.await(3,TimeUnit.SECONDS);assertTrue("Boundary "+boundary+" firstCompleted="+firstDone[0]+" secondPosition="+players[1].getCurrentPosition()+" error="+error[0],finished && error[0].equals("none"));
            }finally{getInstrumentation().runOnMainSync(new Runnable(){public void run(){for(MediaPlayer player:players)if(player!=null)player.release();}});}
        }}finally{fixture.delete();}

    }
    private void le(DataOutputStream out,int value,int bytes)throws IOException{for(int n=0;n<bytes;n++)out.writeByte(value>>(8*n));}
}
