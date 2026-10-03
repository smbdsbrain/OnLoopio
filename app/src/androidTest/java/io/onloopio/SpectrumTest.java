package io.onloopio;

import android.content.Context;
import android.media.AudioManager;
import android.test.InstrumentationTestCase;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.DeviceSettings;
import io.onloopio.library.LocalMusicScanner;
import io.onloopio.library.MusicPaths;
import io.onloopio.model.Song;
import io.onloopio.player.PlaybackService;
import io.onloopio.player.Spectrum;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The platform Visualizer must deliver a non-empty FFT for a generated two-tone WAV and go quiet after STOP. */
public final class SpectrumTest extends InstrumentationTestCase {
    private Context context;private MetadataStore store;private List<MetadataStore.LocalEntry> previous;private File fixture;private Song song;private DeviceSettings prefs;private boolean offline,paused;private AudioManager audio;private int volume;
    protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext();prefs=new DeviceSettings(context);offline=prefs.flag("force_offline",false);paused=prefs.flag("downloads_paused",false);prefs.setFlag("force_offline",true);prefs.setFlag("downloads_paused",true);
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);audio.setStreamVolume(AudioManager.STREAM_MUSIC,1,0);PlaybackService.action(context,PlaybackService.STOP);Thread.sleep(600);
        store=new MetadataStore(context);previous=store.localEntries();fixture=new File(MusicPaths.root(),"_OnLoopio spectrum QA "+System.nanoTime()+"/Album");assertTrue(fixture.mkdirs());
        FileOutputStream out=new FileOutputStream(new File(fixture,"tones.wav"));try{out.write(tones(44100,8,440,3000));}finally{out.close();}
        List<MetadataStore.LocalEntry> entries=new LocalMusicScanner().scan(fixture.getParentFile(),Collections.<String>emptySet(),Collections.<MetadataStore.LocalEntry>emptyList(),null);assertEquals(1,entries.size());song=entries.get(0).song;
        List<MetadataStore.LocalEntry> merged=new ArrayList<MetadataStore.LocalEntry>(previous);merged.addAll(entries);store.replaceLocalSongs(merged);
    }
    protected void tearDown()throws Exception{
        try{PlaybackService.action(context,PlaybackService.STOP);Thread.sleep(600);store.replaceLocalSongs(previous);store.close();File[] files=fixture.listFiles();if(files!=null)for(File f:files)assertTrue(f.delete());assertTrue(fixture.delete());assertTrue(fixture.getParentFile().delete());prefs.setFlag("force_offline",offline);prefs.setFlag("downloads_paused",paused);audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);}finally{super.tearDown();}
    }
    /** 16-bit mono PCM WAV with two equal sines at half scale. */
    static byte[] tones(int rate,int seconds,double f1,double f2){
        int samples=rate*seconds;byte[] wav=new byte[44+samples*2];java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36+samples*2).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(samples*2);
        for(int n=0;n<samples;n++){double t=n/(double)rate;b.putShort((short)(8000*(Math.sin(2*Math.PI*f1*t)+Math.sin(2*Math.PI*f2*t))));}return wav;
    }
    public void testVisualizerDeliversSpectrumWhilePlaying()throws Exception{
        Spectrum.wanted=true;PlaybackService.play(context,Collections.singletonList(song),0,false);
        for(int n=0;n<120 && !(PlaybackService.state.playing && PlaybackService.state.song!=null && song.id.equals(PlaybackService.state.song.id));n++)Thread.sleep(100);
        assertTrue("Tone WAV did not play: "+PlaybackService.state.message,PlaybackService.state.playing);
        int before=Spectrum.captures;float peak=0;int peakBand=-1;
        for(int n=0;n<60;n++){Thread.sleep(100);float[] bands=Spectrum.bands;for(int b=0;b<bands.length;b++)if(bands[b]>peak){peak=bands[b];peakBand=b;}if(Spectrum.captures-before>=10 && peak>.3f)break;}
        android.util.Log.i("OnLoopioTest","SPECTRUM captures="+(Spectrum.captures-before)+" peak="+peak+" band="+peakBand+" status="+Spectrum.status+" bands="+java.util.Arrays.toString(Spectrum.bands));
        assertEquals("Visualizer failed: "+Spectrum.status,"",Spectrum.status);
        assertTrue("No FFT captures arrived",Spectrum.captures-before>=10);
        assertTrue("Spectrum stayed flat, peak="+peak,peak>.3f);
        assertTrue("Effect not enabled while playing",Spectrum.effectEnabled());
        PlaybackService.action(context,PlaybackService.PAUSE);Thread.sleep(600);
        float[] paused=Spectrum.bands;for(float level:paused)assertEquals("Bands not cleared after PAUSE",0f,level);
        // The service tick keeps calling sync every 500 ms during the pause; the deferred disable must still fire.
        for(int n=0;n<40 && Spectrum.effectEnabled();n++)Thread.sleep(100);
        assertFalse("Native Visualizer still enabled "+Spectrum.DISABLE_DELAY_MS+" ms plus after PAUSE",Spectrum.effectEnabled());
        PlaybackService.action(context,PlaybackService.RESUME);
        for(int n=0;n<40 && !Spectrum.effectEnabled();n++)Thread.sleep(100);
        assertTrue("Effect not re-enabled after RESUME",Spectrum.effectEnabled());
        PlaybackService.action(context,PlaybackService.STOP);Thread.sleep(1200);
        float[] idle=Spectrum.bands;for(float level:idle)assertEquals("Bands not cleared after STOP",0f,level);
    }
}
