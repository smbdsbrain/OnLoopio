package io.onloopio;

import android.app.Activity;
import android.app.Instrumentation.ActivityMonitor;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.RenamingDelegatingContext;
import android.view.KeyEvent;
import android.widget.ListView;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.ControlLock;
import io.onloopio.device.DeviceSettings;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Song;
import io.onloopio.player.AudioCache;
import io.onloopio.model.Library;
import io.onloopio.ui.CacheSettingsActivity;
import io.onloopio.ui.DownloadsActivity;
import io.onloopio.ui.PlaylistActivity;
import io.onloopio.ui.SettingsActivity;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Actual Y1 lists/gestures with isolated metadata; no server writes or audio removal. */
public final class MenuNavigationTest extends InstrumentationTestCase {
    private Context context;private PlaylistActivity home;private MetadataStore store,originalStore;
    private Context storeContext;
    private DeviceSettings prefs;private boolean offline,paused,automatic,locked;private File file;
    private android.content.SharedPreferences restore;
    private final List<Song> songs=new ArrayList<Song>();private final List<Playlist> playlists=new ArrayList<Playlist>();
    private List<Song> playlistSongs;
    private Activity extra;
    protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext();prefs=new DeviceSettings(context);
        restore=context.getSharedPreferences("navigation_test_restore",0);
        if(restore.getBoolean("pending",false)){prefs.setFlag("force_offline",restore.getBoolean("offline",false));prefs.setFlag("downloads_paused",restore.getBoolean("paused",false));prefs.setFlag("playlist_auto_sync",restore.getBoolean("automatic",true));ControlLock.locked(context,restore.getBoolean("locked",false));}
        offline=prefs.flag("force_offline",false);paused=prefs.flag("downloads_paused",false);automatic=prefs.flag("playlist_auto_sync",true);locked=ControlLock.locked(context);
        assertTrue(restore.edit().putBoolean("pending",true).putBoolean("offline",offline).putBoolean("paused",paused).putBoolean("automatic",automatic).putBoolean("locked",locked).commit());
        try{prepare();}catch(Throwable failure){cleanup();if(failure instanceof Exception)throw (Exception)failure;throw (Error)failure;}
    }
    private void prepare()throws Exception{
        prefs.setFlag("force_offline",true);prefs.setFlag("downloads_paused",true);prefs.setFlag("playlist_auto_sync",false);ControlLock.locked(context,false);
        home=((OnLoopioTestRunner)getInstrumentation()).awaitHome();assertNotNull(home);
        originalStore=(MetadataStore)field(home,"store");AudioCache cache=new AudioCache(context,new io.onloopio.config.ConfigStore(context).load());Song saved=null;
        for(Song song:originalStore.offlineSongs(cache.completedNames()))if(cache.contains(song)){saved=song;break;}assertNotNull("A cached track is required for offline playlist navigation",saved);
        playlistSongs=Arrays.asList(saved,saved,saved);
        for(String name:context.databaseList())if(name.matches("navigation_[0-9]+_onloopio\\.db"))assertTrue(context.deleteDatabase(name));
        storeContext=new RenamingDelegatingContext(context,"navigation_"+System.nanoTime()+"_");store=new MetadataStore(storeContext);store.selectAccount("navigation-fixture");
        for(File old:context.getFilesDir().listFiles())if(old.getName().matches("navigation-test-[0-9]+")){
            assertTrue(old.isDirectory());assertEquals(context.getFilesDir().getCanonicalPath(),old.getParentFile().getCanonicalPath());
            for(File audio:old.listFiles()){assertTrue(audio.getName().matches("[0-9]+\\.wav"));assertEquals(0,audio.length());assertTrue(audio.delete());}assertTrue(old.delete());
        }
        file=new File(context.getFilesDir(),"navigation-test-"+System.nanoTime());assertTrue(file.mkdir());
        List<MetadataStore.LocalEntry> entries=new ArrayList<MetadataStore.LocalEntry>();
        for(int n=0;n<24;n++){
            File audio=new File(file,n+".wav");assertTrue(audio.createNewFile());
            Song song=new Song("local:navigation-"+n,String.format(java.util.Locale.US,"Navigation track %02d",n),"Navigation artist "+n/4,"Navigation album "+n/3,"wav",60,"",n,"","Navigation genre "+n/6,0,"",audio.getAbsolutePath());
            songs.add(song);entries.add(new MetadataStore.LocalEntry(song,0,file.lastModified()));
        }
        store.replaceLocalSongs(entries);
        for(int n=0;n<10;n++){Playlist p=new Playlist("navigation-"+n,"Navigation playlist "+n,"fixture",3,180);playlists.add(p);store.saveDetail(new PlaylistDetail(p,playlistSongs));}
        store.replacePlaylists(playlists);
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{originalStore=(MetadataStore)field(home,"store");set(home,"store",store);home.openLibrary();}catch(Exception e){throw new RuntimeException(e);}}});idle();
    }
    protected void tearDown()throws Exception{
        try{cleanup();}finally{super.tearDown();}
    }
    private void cleanup()throws Exception{
        try{
            getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{if(extra!=null)extra.finish();if(originalStore!=null){set(home,"store",originalStore);home.openLibrary();}}catch(Exception e){throw new RuntimeException(e);}}});idle();
            if(store!=null){store.close();assertTrue(storeContext.deleteDatabase("onloopio.db"));}
            if(file!=null && file.exists()){for(File audio:file.listFiles()){assertEquals(file.getCanonicalPath(),audio.getParentFile().getCanonicalPath());assertTrue(audio.delete());}assertTrue(file.delete());}
        }finally{prefs.setFlag("force_offline",offline);prefs.setFlag("downloads_paused",paused);prefs.setFlag("playlist_auto_sync",automatic);ControlLock.locked(context,locked);assertTrue(restore.edit().clear().commit());}
    }
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private void idle()throws Exception{Thread.sleep(200);getInstrumentation().waitForIdleSync();}
    private void ui(final Runnable action){
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){action.run();return;}
        final Throwable[] failure={null};getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{action.run();}catch(Throwable e){failure[0]=e;}}});
        if(failure[0] instanceof Error)throw (Error)failure[0];if(failure[0] instanceof RuntimeException)throw (RuntimeException)failure[0];if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }
    private ListView list(final Activity activity){final ListView[] result={null};ui(new Runnable(){public void run(){result[0]=(ListView)LauncherTest.findList(activity.getWindow().getDecorView());assertNotNull(result[0]);}});return result[0];}
    private String label(final ListView list,final int n){final String[] result={null};ui(new Runnable(){public void run(){Object item=list.getAdapter().getItem(n);try{result[0]=item instanceof String?(String)item:(String)field(item,"label");}catch(Exception e){throw new RuntimeException(e);}}});return result[0];}
    private int count(final Activity activity){final int[] result={0};ui(new Runnable(){public void run(){result[0]=list(activity).getCount();}});return result[0];}
    private int selected(final Activity activity){final int[] result={-1};ui(new Runnable(){public void run(){result[0]=list(activity).getSelectedItemPosition();}});return result[0];}
    private int top(final Activity activity){final int[] result={0};ui(new Runnable(){public void run(){result[0]=list(activity).getSelectedView().getTop();}});return result[0];}
    private void select(final Activity activity,final int position)throws Exception{getInstrumentation().runOnMainSync(new Runnable(){public void run(){list(activity).setSelectionFromTop(position,0);}});idle();}
    private void click(final Activity activity,final int position)throws Exception{getInstrumentation().runOnMainSync(new Runnable(){public void run(){ListView list=list(activity);list.performItemClick(null,position,position);}});Thread.sleep(550);getInstrumentation().waitForIdleSync();}
    private void clickLabel(final Activity activity,final String text)throws Exception{final int[] found={-1};ui(new Runnable(){public void run(){ListView list=list(activity);for(int n=0;n<list.getCount();n++)if(text.equals(label(list,n)))found[0]=n;}});assertTrue("Missing action "+text,found[0]>=0);click(activity,found[0]);}
    private void back(final Activity activity)throws Exception{getInstrumentation().runOnMainSync(new Runnable(){public void run(){activity.onBackPressed();}});idle();}
    private void key(final Activity activity,final int action,final int code){getInstrumentation().runOnMainSync(new Runnable(){public void run(){long now=SystemClock.uptimeMillis();activity.dispatchKeyEvent(new KeyEvent(now,now,action,code,0));}});}
    private void hold()throws Exception{key(home,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER);Thread.sleep(750);key(home,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER);idle();assertEquals("Play now",label(list(home),0));}
    private void assertSelection(Activity activity,int index,String row)throws Exception{idle();assertEquals("Parent inherited a child selection",index,selected(activity));assertEquals(row,label(list(activity),index));}
    private String selectRow(Activity activity,int index)throws Exception{select(activity,index);return label(list(activity),index);}
    private void root()throws Exception{getInstrumentation().runOnMainSync(new Runnable(){public void run(){home.openLibrary();}});idle();}
    public void testPlaylistSubscriptionActionsAndBothBackPathsRestoreRow()throws Exception{
        click(home,0);String row=selectRow(home,7);int top=top(home);hold();
        // An empty member snapshot tests subscription navigation without starting downloads.
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{set(home,"contextTracks",Collections.<Song>emptyList());}catch(Exception e){throw new RuntimeException(e);}}});
        clickLabel(home,"Keep playlist offline");assertTrue(store.followsPlaylist("navigation-7"));assertSelection(home,7,"✓ "+row);assertEquals(top,top(home));
        hold();clickLabel(home,"Stop following updates");assertFalse(store.followsPlaylist("navigation-7"));assertSelection(home,7,row);
        hold();select(home,5);back(home);assertSelection(home,7,row);
        hold();clickLabel(home,"Back");assertSelection(home,7,row);
    }
    public void testEveryLibraryContextRestoresEntityAndScroll()throws Exception{
        for(Song song:songs)store.toggleLike(store.accountKey(),song.id);
        for(int section:new int[]{0,1,2,3,4,7}){
            root();click(home,section);int selected=Math.min(4,count(home)-2);String row=selectRow(home,selected);int top=top(home);hold();
            clickLabel(home,"Details");back(home);assertSelection(home,selected,row);assertEquals("Context changed the viewport in section "+section,top,top(home));
        }
        root();click(home,0);click(home,6);String row=selectRow(home,2);hold();clickLabel(home,"Back");assertSelection(home,2,row);
    }
    public void testLikeRefreshAndRemovedFavoriteChooseNeighbor()throws Exception{
        for(Song song:songs)store.toggleLike(store.accountKey(),song.id);
        click(home,7);select(home,6);String next=label(list(home),7);hold();clickLabel(home,"Remove like");
        assertEquals("Like",label(list(home),8));assertEquals("Like refresh lost menu focus",8,selected(home));
        back(home);assertSelection(home,6,next);
    }
    public void testRemovedLastFavoriteSelectsPreviousTrack()throws Exception{
        for(Song song:songs)store.toggleLike(store.accountKey(),song.id);
        click(home,7);int last=count(home)-2;String previous=label(list(home),last-1);select(home,last);hold();clickLabel(home,"Remove like");
        back(home);assertSelection(home,last-1,previous);
    }
    public void testPlaylistReorderWhileContextOpenFollowsIdentity()throws Exception{
        click(home,0);select(home,4);hold();Playlist changed=new Playlist("navigation-4","ZZZ moved playlist","new",3,180);playlists.set(4,changed);
        store.replacePlaylists(playlists);store.saveDetail(new PlaylistDetail(changed,playlistSongs));
        context.sendBroadcast(new Intent(io.onloopio.sync.PlaylistSyncService.UPDATED).putExtra("reachable",true));idle();
        back(home);assertSelection(home,9,"ZZZ moved playlist   3");
    }
    public void testRemovedPlaylistWhileTrackContextOpenReturnsToPlaylistNeighbor()throws Exception{
        click(home,0);click(home,6);select(home,2);hold();playlists.remove(6);store.replacePlaylists(playlists);
        context.sendBroadcast(new Intent(io.onloopio.sync.PlaylistSyncService.UPDATED).putExtra("reachable",true));idle();
        back(home);assertSelection(home,6,"Navigation playlist 7   3");
    }
    public void testBackRowsRestoreLibraryParentsAndExternalActivityReturn()throws Exception{
        select(home,2);click(home,2);String album=selectRow(home,5);click(home,5);clickLabel(home,"Back");assertSelection(home,5,album);
        clickLabel(home,"Back to main menu");assertSelection(home,2,"Albums");
        click(home,3);String track=selectRow(home,12);
        extra=open(SettingsActivity.class);select(extra,5);back(extra);extra=null;assertSelection(home,12,track);
    }
    private <T extends Activity> T open(Class<T> type)throws Exception{
        ActivityMonitor monitor=getInstrumentation().addMonitor(type.getName(),null,false);
        context.startActivity(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity activity=monitor.waitForActivityWithTimeout(5000);getInstrumentation().removeMonitor(monitor);assertNotNull(activity);idle();return type.cast(activity);
    }
    public void testPlayerPowerCancelReturnsToPowerAction()throws Exception{
        back(home);key(home,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU);key(home,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU);idle();
        clickLabel(home,"Power off");select(home,1);back(home);assertSelection(home,3,"Power off");
        clickLabel(home,"Power off");clickLabel(home,"Cancel");assertSelection(home,3,"Power off");
    }
    public void testSettingsChoiceApplyAndCancelKeepParentPosition()throws Exception{
        extra=open(SettingsActivity.class);String row=selectRow(extra,4);click(extra,4);int original=selected(extra);
        select(extra,7);back(extra);assertSelection(extra,4,row);
        click(extra,4);click(extra,original);assertSelection(extra,4,row);
        select(extra,5);click(extra,5);select(extra,9);back(extra);assertEquals(5,selected(extra));
    }
    public void testNestedProfileConfirmationKeepsEachParentSelection()throws Exception{
        extra=open(CacheSettingsActivity.class);click(extra,0);String profile=selectRow(extra,2);click(extra,2);clickLabel(extra,"Cancel");assertSelection(extra,2,profile);
        click(extra,2);select(extra,1);back(extra);assertSelection(extra,2,profile);back(extra);assertSelection(extra,0,"Rotation profiles");
    }
    public void testDownloadContextBackAndActionKeepJobPosition()throws Exception{
        List<String> ids=new ArrayList<String>();Library catalog=new Library();for(int n=0;n<24;n++){Song song=new Song("navigation-download-"+n,"Navigation download "+n,"QA","QA","mp3",60);catalog.songs.add(song);ids.add(song.id);}store.replaceCatalog(catalog);store.enqueueDownloads(ids);
        extra=open(DownloadsActivity.class);final Activity downloads=extra;final MetadataStore original=(MetadataStore)field(downloads,"store");
        try{
            getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{set(downloads,"store",store);Method render=downloads.getClass().getSuperclass().getDeclaredMethod("render");render.setAccessible(true);render.invoke(downloads);}catch(Exception e){throw new RuntimeException(e);}}});idle();
            String job=selectRow(downloads,10);click(downloads,10);clickLabel(downloads,"Back");assertSelection(downloads,10,job);
            click(downloads,10);clickLabel(downloads,"Protect from cleanup");assertSelection(downloads,10,job);
            click(downloads,10);select(downloads,1);back(downloads);assertSelection(downloads,10,job);
            final String[] id={null};ui(new Runnable(){public void run(){try{id[0]=((String)field(list(downloads).getAdapter().getItem(10),"key")).substring("download:".length());}catch(Exception e){throw new RuntimeException(e);}}});
            click(downloads,10);android.content.ContentValues position=new android.content.ContentValues();position.put("position",-1);store.getWritableDatabase().update("download_queue",position,"song_id=?",new String[]{id[0]});
            back(downloads);assertSelection(downloads,3,job);
        }finally{getInstrumentation().runOnMainSync(new Runnable(){public void run(){try{set(downloads,"store",original);downloads.finish();}catch(Exception e){throw new RuntimeException(e);}}});extra=null;idle();}
    }
}
