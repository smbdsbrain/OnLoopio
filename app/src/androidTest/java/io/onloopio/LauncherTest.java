package io.onloopio;

import android.app.Activity;
import android.app.Instrumentation.ActivityMonitor;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ListView;
import io.onloopio.ui.PlaylistActivity;
import io.onloopio.ui.SettingsActivity;

/** Exercises HOME root protection and the actual wheel menu, not network or cache fixtures. */
public final class LauncherTest extends InstrumentationTestCase {
    private PlaylistActivity home;
    protected void setUp() throws Exception {
        super.setUp();
        home=((OnLoopioTestRunner)getInstrumentation()).awaitHome();
        assertNotNull("HOME did not resume",home);
        getInstrumentation().waitForIdleSync(); Thread.sleep(150);
        getInstrumentation().runOnMainSync(new Runnable() { public void run() {
            home.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            home.openLibrary();
        } });
    }
    private void keys(int... codes) throws Exception {for(int code:codes){sendKeys(code);Thread.sleep(code==KeyEvent.KEYCODE_DPAD_CENTER?550:120);getInstrumentation().waitForIdleSync();}}
    protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(new Runnable() { public void run() {
            home.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        } });
        super.tearDown();
    }
    public void testBackAtRootDoesNotFinish() throws Exception {
        getInstrumentation().runOnMainSync(new Runnable() { public void run() { home.onBackPressed(); home.onBackPressed(); } });
        getInstrumentation().waitForIdleSync(); assertFalse(home.isFinishing());
    }
    public void testWheelMenuHasNoTextEntryAndReturnsHome() throws Exception {
        ActivityMonitor monitor=getInstrumentation().addMonitor(SettingsActivity.class.getName(),null,false);
        sendKeys(KeyEvent.KEYCODE_MENU);
        final Activity menu=monitor.waitForActivityWithTimeout(5000);
        assertNotNull("Menu did not open",menu); getInstrumentation().removeMonitor(monitor);
        assertFalse(hasTextEntry(menu.getWindow().getDecorView()));
        // Y1 clockwise wheel is RIGHT on the older firmware, DOWN on the new ROM.
        sendKeys(KeyEvent.KEYCODE_BACK);
        getInstrumentation().waitForIdleSync(); assertTrue("Wheel-selected return did not close menu",menu.isFinishing());
        assertFalse(home.isFinishing());
    }
    public void testMusicMenuAndCachedLibraryNavigation() throws Exception {
        final ListView list=(ListView)findList(home.getWindow().getDecorView());
        assertNotNull(list); assertEquals("Playlists",list.getAdapter().getItem(0));
        assertEquals("Artists",list.getAdapter().getItem(1)); assertEquals("Albums",list.getAdapter().getItem(2)); assertEquals("Tracks",list.getAdapter().getItem(3));
        assertEquals(8,list.getAdapter().getCount());assertEquals("Genres",list.getAdapter().getItem(4));assertEquals("Now Playing",list.getAdapter().getItem(5));assertEquals("Settings",list.getAdapter().getItem(6));assertEquals("Favorite tracks",list.getAdapter().getItem(7));
        keys(KeyEvent.KEYCODE_DPAD_CENTER);
        for(int n=0;n<100 && list.getAdapter().getCount()<=2;n++) Thread.sleep(100);
        assertTrue("Synced playlists did not open",list.getAdapter().getCount()>2);
        keys(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync(); assertTrue("Artists were not derived from synced metadata",list.getAdapter().getCount()>1);
        keys(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_CENTER);
        io.onloopio.db.MetadataStore metadata=new io.onloopio.db.MetadataStore(getInstrumentation().getTargetContext());String name;
        try {name=metadata.catalogSongs().get(0).album;} finally {metadata.close();}
        final String album=name;final int[] position={-1},count={0};final java.util.List<String> displayed=new java.util.ArrayList<String>();
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){count[0]=list.getAdapter().getCount();for(int n=0;n<count[0];n++)if(album.equals(list.getAdapter().getItem(n))){position[0]=n;break;}for(int n=0;n<Math.min(5,count[0]);n++)displayed.add(list.getAdapter().getItem(n).toString());}});
        assertTrue("Known catalog album missing: "+name+" rows="+count[0]+" first="+displayed,position[0]>=0);final int selected=position[0];
        getInstrumentation().runOnMainSync(new Runnable(){public void run(){list.setSelection(selected);}});Thread.sleep(150);keys(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("Known album tracks did not open",list.getAdapter().getCount()>1);
        keys(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BACK);assertFalse(home.isFinishing());
    }
    static View findList(View view) {
        if(view instanceof ListView) return view;
        if(view instanceof ViewGroup) for(int n=0;n<((ViewGroup)view).getChildCount();n++){ View result=findList(((ViewGroup)view).getChildAt(n)); if(result!=null) return result; }
        return null;
    }
    private boolean hasTextEntry(View view) {
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup) for (int n=0;n<((ViewGroup)view).getChildCount();n++)
            if (hasTextEntry(((ViewGroup)view).getChildAt(n))) return true;
        return false;
    }
}
