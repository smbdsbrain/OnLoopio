package io.onloopio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.graphics.Bitmap;
import android.test.InstrumentationTestCase;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.DeviceSettings;
import io.onloopio.device.UsbStorage;
import io.onloopio.library.LocalMusicScanner;
import io.onloopio.library.MusicPaths;
import io.onloopio.model.Song;
import io.onloopio.player.CoverCache;
import io.onloopio.player.PlaybackService;
import io.onloopio.ui.PlaylistActivity;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native decoders, queue, covers and wheel browsing on Y1 with forced offline mode. */
public final class LocalPlaybackTest extends InstrumentationTestCase {
    private Context context;private MetadataStore store;private List<MetadataStore.LocalEntry> previous;private File fixture;private List<Song> songs;private DeviceSettings prefs;private boolean offline,paused;private AudioManager audio;private int volume;
    protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext();prefs=new DeviceSettings(context);offline=prefs.flag("force_offline",false);paused=prefs.flag("downloads_paused",false);prefs.setFlag("force_offline",true);prefs.setFlag("downloads_paused",true);
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);PlaybackService.action(context,PlaybackService.STOP);Thread.sleep(600);
        store=new MetadataStore(context);previous=store.localEntries();fixture=new File(MusicPaths.root(),"_OnLoopio playback QA "+System.nanoTime()+"/Album");assertTrue(fixture.mkdirs());
        for(String ext:new String[]{"mp3","flac","wav","m4a","aac","ogg"}){FileOutputStream out=new FileOutputStream(new File(fixture,"fixture."+ext));InputStream in=getInstrumentation().getContext().getAssets().open("music/fixture."+ext);try{byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}finally{in.close();out.close();}}
        List<MetadataStore.LocalEntry> entries=new LocalMusicScanner().scan(fixture.getParentFile(),Collections.<String>emptySet(),Collections.<MetadataStore.LocalEntry>emptyList(),null);songs=new ArrayList<Song>();for(MetadataStore.LocalEntry e:entries)songs.add(e.song);List<MetadataStore.LocalEntry> merged=new ArrayList<MetadataStore.LocalEntry>(previous);merged.addAll(entries);store.replaceLocalSongs(merged);
    }
    protected void tearDown()throws Exception{
        try{PlaybackService.action(context,PlaybackService.STOP);Thread.sleep(600);store.replaceLocalSongs(previous);store.close();File[] files=fixture.listFiles();if(files!=null)for(File f:files){assertEquals(fixture.getCanonicalPath(),f.getParentFile().getCanonicalPath());assertTrue(f.delete());}assertTrue(fixture.delete());assertTrue(fixture.getParentFile().delete());prefs.setFlag("force_offline",offline);prefs.setFlag("downloads_paused",paused);audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);}finally{super.tearDown();}
    }
    private Song song(String ext){for(Song s:songs)if(ext.equals(s.suffix))return s;throw new IllegalStateException("Missing fixture "+ext);}
    private void playing(Song s)throws Exception{for(int n=0;n<120 && !(PlaybackService.state.playing && PlaybackService.state.song!=null && s.id.equals(PlaybackService.state.song.id));n++)Thread.sleep(100);assertTrue(s.suffix+": "+PlaybackService.state.message,PlaybackService.state.playing);assertEquals(s.id,PlaybackService.state.song.id);}
    public void testSixLocalFormatsPlayAndSeekWithoutNetwork()throws Exception{
        for(String ext:new String[]{"mp3","flac","wav","m4a","aac","ogg"}){Song s=song(ext);PlaybackService.play(context,Collections.singletonList(s),0,false);playing(s);Thread.sleep(1200);assertTrue("Position did not advance: "+ext,PlaybackService.state.position>=500);context.startService(new Intent(context,PlaybackService.class).setAction(PlaybackService.SEEK).putExtra("seconds",5));for(int n=0;n<35 && PlaybackService.state.position<4000;n++)Thread.sleep(100);assertTrue("Seek failed: "+ext,PlaybackService.state.position>=4000);PlaybackService.action(context,PlaybackService.STOP);Thread.sleep(600);}
    }
    public void testLocalArtworkAndPlaybackQueue()throws Exception{
        FileOutputStream out=new FileOutputStream(new File(fixture,"cover.png"));Bitmap cover=Bitmap.createBitmap(32,32,Bitmap.Config.RGB_565);cover.eraseColor(0xff00ffff);try{cover.compress(Bitmap.CompressFormat.PNG,100,out);}finally{out.close();cover.recycle();}
        Bitmap loaded=new CoverCache(context,null).load(song("wav"),null,false);assertNotNull("Folder cover missing without server",loaded);assertEquals(32,loaded.getWidth());loaded.recycle();
        PlaybackService.play(context,java.util.Arrays.asList(song("wav"),song("mp3")),0,false);playing(song("wav"));PlaybackService.action(context,PlaybackService.NEXT);playing(song("mp3"));
    }
    public void testDoublePlayLikesWithoutPausingAndFavoriteMenuRemovesLike()throws Exception{
        final PlaylistActivity home=((OnLoopioTestRunner)getInstrumentation()).awaitHome();assertNotNull(home);
        final Song track=song("wav");PlaybackService.play(context,Collections.singletonList(track),0,false);playing(track);
        assertFalse(store.isLiked(track.id));mediaTap(home);Thread.sleep(100);mediaTap(home);Thread.sleep(600);
        assertTrue("Double Play did not like",store.isLiked(track.id));assertTrue("Double Play paused audio",PlaybackService.state.playing);assertTrue(PlaybackService.state.liked);
        mediaTap(home);Thread.sleep(650);assertFalse("Single Play did not pause",PlaybackService.state.playing);
        // Use the media receiver path while paused; it shares the service's gesture state.
        broadcastTap();Thread.sleep(100);broadcastTap();Thread.sleep(600);assertFalse("Receiver double Play did not unlike",store.isLiked(track.id));assertFalse("Receiver double Play resumed",PlaybackService.state.playing);
        store.toggleLike(store.accountKey(),track.id);io.onloopio.sync.PlaylistSyncService.feedbackChanged(context);
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){home.openLibrary();android.widget.ListView list=(android.widget.ListView)LauncherTest.findList(home.getWindow().getDecorView());assertEquals("Favorite tracks",list.getAdapter().getItem(7));list.performItemClick(null,7,7);}});Thread.sleep(600);
        final int[] selected={-1};getInstrumentation().runOnMainSync(new Runnable(){public void run(){android.widget.ListView list=(android.widget.ListView)LauncherTest.findList(home.getWindow().getDecorView());for(int n=0;n<list.getAdapter().getCount();n++)if(list.getAdapter().getItem(n).toString().contains(track.title)){selected[0]=n;assertTrue(list.getAdapter().getItem(n).toString().startsWith("♥"));list.setSelection(n);break;}}});assertTrue("Local favorite absent",selected[0]>=0);Thread.sleep(200);
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){long now=android.os.SystemClock.uptimeMillis();home.dispatchKeyEvent(new android.view.KeyEvent(now,now,0,android.view.KeyEvent.KEYCODE_DPAD_CENTER,0));}});Thread.sleep(750);
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){long now=android.os.SystemClock.uptimeMillis();home.dispatchKeyEvent(new android.view.KeyEvent(now,now,1,android.view.KeyEvent.KEYCODE_DPAD_CENTER,0));android.widget.ListView list=(android.widget.ListView)LauncherTest.findList(home.getWindow().getDecorView());int found=-1;for(int n=0;n<list.getAdapter().getCount();n++)if("Remove like".equals(list.getAdapter().getItem(n)))found=n;assertTrue("Track menu lacks unlike",found>=0);list.performItemClick(null,found,found);}});Thread.sleep(600);
        assertFalse(store.isLiked(track.id));assertEquals(0,store.pendingFeedback(store.accountKey()));
    }
    private void mediaTap(final PlaylistActivity home){getInstrumentation().runOnMainSync(new Runnable(){public void run(){long now=android.os.SystemClock.uptimeMillis();home.dispatchKeyEvent(new android.view.KeyEvent(now,now,0,android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0));home.dispatchKeyEvent(new android.view.KeyEvent(now,now+1,1,android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0));}});}
    private void broadcastTap(){long now=android.os.SystemClock.uptimeMillis();io.onloopio.player.MediaButtons receiver=new io.onloopio.player.MediaButtons();receiver.onReceive(context,new Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,new android.view.KeyEvent(now,now,0,android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0)));receiver.onReceive(context,new Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,new android.view.KeyEvent(now,now+1,1,android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0)));}
    public void testLocalTracksBrowseAndDoubleClickWithWheelControls()throws Exception{
        final PlaylistActivity home=((OnLoopioTestRunner)getInstrumentation()).awaitHome();assertNotNull(home);
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){home.openLibrary();}});
        final android.widget.ListView list=(android.widget.ListView)LauncherTest.findList(home.getWindow().getDecorView());assertNotNull(list);
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){assertEquals(8,list.getAdapter().getCount());assertEquals("Now Playing",list.getAdapter().getItem(5));assertEquals("Settings",list.getAdapter().getItem(6));assertEquals("Favorite tracks",list.getAdapter().getItem(7));list.performItemClick(null,3,3);}});Thread.sleep(700);getInstrumentation().waitForIdleSync();
        final int[] found={-1};getInstrumentation().runOnMainSync(new Runnable(){public void run(){for(int n=0;n<list.getAdapter().getCount();n++){String label=list.getAdapter().getItem(n).toString();if(label.contains(song("mp3").title) && label.contains(song("mp3").artist)){assertFalse(label.contains("Local file"));assertFalse(label.startsWith("✓"));found[0]=n;break;}}}});int index=found[0];assertTrue("Imported music missing from common offline Tracks",index>=0);
        final int selected=index;getInstrumentation().runOnMainSync(new Runnable(){public void run(){list.setSelection(selected);}});Thread.sleep(200);
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){long t=android.os.SystemClock.uptimeMillis();for(int n=0;n<2;n++){home.dispatchKeyEvent(new android.view.KeyEvent(t,t,0,android.view.KeyEvent.KEYCODE_DPAD_CENTER,0));home.dispatchKeyEvent(new android.view.KeyEvent(t,t,1,android.view.KeyEvent.KEYCODE_DPAD_CENTER,0));}}});Thread.sleep(1200);
        assertTrue(PlaybackService.state.message,PlaybackService.state.playing);assertTrue(PlaybackService.state.song.local());assertNotNull(home.getWindow().getDecorView().findViewWithTag("player"));
    }
    public void testStockUsbStorageApiAndSystemPermission()throws Exception{
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkCallingOrSelfPermission("android.permission.MOUNT_UNMOUNT_FILESYSTEMS"));assertTrue("USB cable unavailable",UsbStorage.connected(context));assertFalse("Storage unexpectedly shared",UsbStorage.enabled(context));
    }
    public void testCommonArtistAndAlbumContainDownloadsAndImportsInBothModes()throws Exception{
        io.onloopio.api.ServerConfig config=new io.onloopio.config.ConfigStore(context).load();assertNotNull(config);
        io.onloopio.player.AudioCache cache=new io.onloopio.player.AudioCache(context,config);Song downloaded=null;
        for(Song candidate:store.offlineSongs(cache.completedNames())){
            if(candidate.artistId.length()==0 || candidate.albumId.length()==0 || !cache.contains(candidate))continue;
            int albums=0,artists=0;for(io.onloopio.model.Library.Entity e:store.albums())if(e.name.equalsIgnoreCase(candidate.album) && e.artist.equalsIgnoreCase(candidate.artist))albums++;
            for(io.onloopio.model.Library.Entity e:store.artists())if(e.name.equalsIgnoreCase(candidate.artist))artists++;
            if(albums==1 && artists==1){downloaded=candidate;break;}
        }
        assertNotNull("No unambiguous saved album fixture",downloaded);final Song saved=downloaded;
        File hold=new File(context.getFilesDir(),"music-library.hold");assertTrue("External library hold exists",hold.createNewFile());
        try{
            for(int n=0;n<100 && io.onloopio.library.MusicLibraryService.running;n++)Thread.sleep(100);assertFalse(io.onloopio.library.MusicLibraryService.running);
            Song wav=song("wav");Song imported=new Song(wav.id,"OnLoopio merged album QA",saved.artist.toLowerCase(java.util.Locale.US),saved.album.toLowerCase(java.util.Locale.US),wav.suffix,wav.duration,"",99,"","",0,"",wav.localPath);
            List<MetadataStore.LocalEntry> combined=store.localEntries();for(int n=0;n<combined.size();n++){MetadataStore.LocalEntry entry=combined.get(n);if(entry.song.id.equals(wav.id))combined.set(n,new MetadataStore.LocalEntry(imported,entry.bytes,entry.modified));}store.replaceLocalSongs(combined);
            final PlaylistActivity home=((OnLoopioTestRunner)getInstrumentation()).awaitHome();
            for(boolean forced:new boolean[]{true,false}){
                prefs.setFlag("force_offline",forced);
                if(!forced){java.lang.reflect.Field field=PlaylistActivity.class.getDeclaredField("online");field.setAccessible(true);assertTrue("Home server unavailable",((io.onloopio.device.OnlineMode)field.get(home)).check(config));}
                getInstrumentation().runOnMainSync(new Runnable(){public void run(){home.openLibrary();}});
                clickUniqueRow(home,"Artists");clickUniqueRow(home,saved.artist);clickUniqueRow(home,saved.album);
                final boolean[] visible={false,false};getInstrumentation().runOnMainSync(new Runnable(){public void run(){android.widget.ListView list=(android.widget.ListView)LauncherTest.findList(home.getWindow().getDecorView());for(int n=0;n<list.getAdapter().getCount();n++){String label=list.getAdapter().getItem(n).toString();if(label.contains(saved.title))visible[0]=true;if(label.contains("OnLoopio merged album QA"))visible[1]=true;assertFalse("Source badge remained",label.contains("Local file"));}}});
                assertTrue("Saved track missing in common album",visible[0]);assertTrue("Imported track split into a separate album",visible[1]);
            }
        }finally{assertTrue(hold.delete());}
    }
    private void clickUniqueRow(final PlaylistActivity home,final String name)throws Exception{
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){android.widget.ListView list=(android.widget.ListView)LauncherTest.findList(home.getWindow().getDecorView());int position=-1,matches=0;for(int n=0;n<list.getAdapter().getCount();n++)if(name.equalsIgnoreCase(list.getAdapter().getItem(n).toString())){position=n;matches++;}assertEquals("Duplicated/missing common group: "+name,1,matches);list.performItemClick(null,position,position);}});Thread.sleep(700);getInstrumentation().waitForIdleSync();
    }
}
