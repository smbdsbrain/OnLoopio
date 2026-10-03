package io.onloopio.ui;

import android.app.AlarmManager;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import io.onloopio.config.ConfigStore;
import io.onloopio.device.PowerReceiver;
import io.onloopio.player.AudioCache;
import io.onloopio.player.PlaybackService;
import io.onloopio.db.MetadataStore;
import io.onloopio.device.UsbStorage;
import io.onloopio.library.MusicLibraryService;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

/** Stock essentials recreated with wheel-only controls; accounts remain provisioned over USB. */
public final class SettingsActivity extends WheelActivity {
    private final android.os.Handler storageTimer=new android.os.Handler();private String lastStorageStatus;
    private boolean storageShared(){try{return UsbStorage.enabled(this);}catch(Exception unavailable){return false;}}
    private final Runnable refreshStorage=new Runnable(){public void run(){String signature=MusicLibraryService.status+":"+storageShared();if(!signature.equals(lastStorageStatus)){lastStorageStatus=signature;if(stack.size()==1){remember();render();}}storageTimer.postDelayed(this,1000);}};
    protected void onResume(){super.onResume();storageTimer.post(refreshStorage);}
    protected void onPause(){storageTimer.removeCallbacks(refreshStorage);super.onPause();}
    private static final int[] TIMEOUTS={10000,15000,30000,45000,60000,120000,300000,Integer.MAX_VALUE};
    private static final int[] TIMERS={0,10,20,30,60,90,120};
    private static final String[] REPEAT={"Off","One track","All tracks"};
    private AudioManager audio() { return (AudioManager)getSystemService(AUDIO_SERVICE); }
    private static int find(int[] values,int current) { for(int n=0;n<values.length;n++) if(values[n]==current) return n; return 0; }
    private void changed() { PlaybackService.action(this,PlaybackService.SETTINGS); }
    private Item row(String label,Runnable action) { return new Item(label,action); }
    private String on(boolean value) { return value?"On":"Off"; }
    private Item toggle(final String label,final String key,final boolean fallback) {
        return row(label+": "+on(prefs.flag(key,fallback)),new Runnable(){ public void run(){ choose(label,new String[]{"Off","On"},prefs.flag(key,fallback)?1:0,new Choice(){ public void apply(int n){ prefs.setFlag(key,n==1); if("key_lock".equals(key)) prefs.wheelLocked(n==1); changed(); }}); }});
    }
    Menu rootMenu() {
        return new Menu("Device settings") { List<Item> items() {
            note=Ui.deviceInfo(SettingsActivity.this)+" · "+Ui.label(SettingsActivity.this,storageShared()?"USB storage shared with computer":MusicLibraryService.status);
            String setup=prefs.text("setup_status","");if(setup.length()>0)note+="\n"+setup;
            List<Item> r=new ArrayList<Item>();
            r.add(row("Bluetooth",new Runnable(){ public void run(){ startActivity(new Intent(SettingsActivity.this,BluetoothActivity.class)); }}));
            r.add(row("Wi-Fi",new Runnable(){ public void run(){ wifiMenu(); }}));
            r.add(toggle("Force offline mode","force_offline",false));
            MetadataStore metadata=new MetadataStore(SettingsActivity.this);int queued;try{queued=metadata.pendingDownloads();}finally{metadata.close();}
            r.add(row("Download queue: "+queued,new Runnable(){ public void run(){ startActivity(new Intent(SettingsActivity.this,DownloadsActivity.class)); }}));
            r.add(row("Screen timeout: "+(prefs.timeout()==Integer.MAX_VALUE?"Always on":prefs.timeout()/1000+" s"),new Runnable(){ public void run(){ choose("Screen timeout",new String[]{"10 seconds","15 seconds","30 seconds","45 seconds","1 minute","2 minutes","5 minutes","Always on"},find(TIMEOUTS,prefs.timeout()),new Choice(){ public void apply(int n){ prefs.timeout(TIMEOUTS[n]); }}); }}));
            r.add(row("Brightness: "+prefs.brightness()*100/255+"%",new Runnable(){ public void run(){ choose("Brightness",new String[]{"10%","20%","30%","40%","50%","60%","70%","80%","90%","100%"},Math.max(0,Math.min(9,prefs.brightness()*10/255-1)),new Choice(){ public void apply(int n){ prefs.brightness((n+1)*255/10); }}); }}));
            r.add(row("Volume: "+audio().getStreamVolume(AudioManager.STREAM_MUSIC),new Runnable(){ public void run(){ numbers("Volume",0,audio().getStreamMaxVolume(AudioManager.STREAM_MUSIC),audio().getStreamVolume(AudioManager.STREAM_MUSIC),new Choice(){ public void apply(int n){ audio().setStreamVolume(AudioManager.STREAM_MUSIC,n,0); }}); }}));
            r.add(toggle("Key lock (screen off)","key_lock",true)); r.add(toggle("Clicker","clicker",false)); r.add(toggle("Vibration","vibration",false));
            r.add(row("Timed power off: "+(prefs.shutdownRemaining()==0?"Off":prefs.shutdownRemaining()+" min"),new Runnable(){ public void run(){ choose("Timed power off",new String[]{"Off","10 minutes","20 minutes","30 minutes","60 minutes","90 minutes","120 minutes"},0,new Choice(){ public void apply(int n){ prefs.shutdownTimer(TIMERS[n]); }}); }}));
            r.add(toggle("Shuffle","shuffle",false));
            r.add(row("Repeat: "+REPEAT[Math.max(0,Math.min(2,prefs.number("repeat",0)))],new Runnable(){ public void run(){ choose("Repeat",REPEAT,prefs.number("repeat",0),new Choice(){ public void apply(int n){ prefs.setNumber("repeat",n); changed(); }}); }}));
            r.add(row("Equalizer",new Runnable(){ public void run(){ equalizerMenu(); }}));
            r.add(toggle("File extensions","file_extensions",false)); r.add(toggle("Battery percentage","battery_percentage",true));
            r.add(row("Date / time",new Runnable(){ public void run(){ clockMenu(); }}));
            r.add(row("Theme",new Runnable(){ public void run(){ choose("Theme",new String[]{"Green","Light","Blue"},prefs.number("theme",0),new Choice(){ public void apply(int n){ prefs.setNumber("theme",n); }}); }}));
            r.add(new Item("Accent color: "+Ui.label(SettingsActivity.this,AccentPalette.NAMES[AccentPalette.selected(prefs)]),new Runnable(){ public void run(){ accentMenu(); }},AccentPalette.color(prefs.number("theme",0),AccentPalette.selected(prefs))));
            r.add(row("Wallpaper",new Runnable(){ public void run(){ wallpaperMenu(Environment.getExternalStorageDirectory()); }}));
            r.add(row("Language",new Runnable(){ public void run(){ choose("Language",new String[]{"English","Русский"},prefs.number("language",0),new Choice(){ public void apply(int n){ prefs.setNumber("language",n); }}); }}));
            r.add(row("Track sorting",new Runnable(){ public void run(){ choose("Track sorting",new String[]{"Natural ascending","Natural descending","Alphabetical ascending","Alphabetical descending"},prefs.number("sort",0),new Choice(){ public void apply(int n){ prefs.setNumber("sort",n); prefs.setFlag("natural_sort",n<2); prefs.setFlag("sort_descending",n%2==1); }}); }}));
            r.add(row("Reset device settings",new Runnable(){ public void run(){ confirm("Reset device preferences? Account and music stay.",new Runnable(){ public void run(){ prefs.reset(); changed(); }}); }}));
            r.add(row("Clear downloaded audio",new Runnable(){ public void run(){ confirm("Delete OnLoopio audio? Account and playlists stay.",new Runnable(){ public void run(){ PlaybackService.action(SettingsActivity.this,PlaybackService.CLEAR); }}); }}));
            r.add(row("About",new Runnable(){ public void run(){ show(new Menu("OnLoopio 0.10.0 · Y1") { List<Item> items(){ note="Y1 3.1.2 · Android 4.2.2\nCenter: volume / seek. Lower button: play / pause.\nFull wheel turn: enable adjustment.\n4 center taps: lock / unlock."; List<Item> r=new ArrayList<Item>(); r.add(row("Back",new Runnable(){ public void run(){ onBackPressed(); }})); return r; }}); }}));
            r.add(row("Playlist synchronization",new Runnable(){public void run(){startActivity(new Intent(SettingsActivity.this,SyncSettingsActivity.class));}}));
            r.add(row("Cache policy",new Runnable(){public void run(){startActivity(new Intent(SettingsActivity.this,CacheSettingsActivity.class));}}));
            r.add(row("Scan Music folder",new Runnable(){public void run(){MusicLibraryService.request(SettingsActivity.this,true);}}));
            final boolean shared=storageShared();
            r.add(row(shared?"Return USB storage to player":"Share Music over USB",new Runnable(){public void run(){if(shared){try{UsbStorage.disable(SettingsActivity.this);}catch(Exception unavailable){throw new IllegalStateException(unavailable);}return;}confirm("Share storage with computer? Playback pauses until storage returns.",new Runnable(){public void run(){try{UsbStorage.enable(SettingsActivity.this);}catch(Exception unavailable){throw new IllegalStateException(unavailable);}}});}}));
            r.add(row("Maintenance",new Runnable(){ public void run(){ confirm("Open Android maintenance settings?",new Runnable(){ public void run(){ startActivity(new Intent(Settings.ACTION_SETTINGS)); }}); }}));
            r.add(row("Power off",new Runnable(){ public void run(){ confirm("Power off the Y1?",new Runnable(){ public void run(){ PowerReceiver.powerOff(SettingsActivity.this); }}); }}));
            r.add(row("Back to main menu",new Runnable(){ public void run(){ finish(); }})); return r;
        }};
    }
    private void accentMenu() {
        Menu menu=new Menu("Accent color") { List<Item> items() {
            List<Item> result=new ArrayList<Item>();int current=AccentPalette.selected(prefs),theme=prefs.number("theme",0);
            for(int n=0;n<AccentPalette.NAMES.length;n++){
                final int value=n;String label=Ui.label(SettingsActivity.this,AccentPalette.NAMES[n])+(n==current?"  ✓":"");
                result.add(new Item(label,new Runnable(){ public void run(){ prefs.setNumber("accent_color",value);onBackPressed(); }},AccentPalette.color(theme,n)));
            }
            result.add(row("Back",new Runnable(){ public void run(){ onBackPressed(); }}));return result;
        }};
        menu.selected=AccentPalette.selected(prefs);show(menu);
    }
    private void numbers(String title,final int min,int max,int current,final Choice change) {
        String[] labels=new String[max-min+1]; for(int n=0;n<labels.length;n++) labels[n]=Integer.toString(min+n);
        choose(title,labels,Math.max(0,Math.min(labels.length-1,current-min)),new Choice(){ public void apply(int n){ change.apply(min+n); }});
    }
    private void wifiMenu() {
        show(new Menu("Wi-Fi") { List<Item> items(){
            final WifiManager wifi=(WifiManager)getSystemService(WIFI_SERVICE); note="New network passwords: configure over USB.\n"+(wifi.isWifiEnabled()?wifi.getConnectionInfo().getSSID():"Wi-Fi is off");
            List<Item> r=new ArrayList<Item>(); r.add(row(wifi.isWifiEnabled()?"Turn Wi-Fi off":"Turn Wi-Fi on",new Runnable(){ public void run(){ if(!wifi.setWifiEnabled(!wifi.isWifiEnabled())) throw new IllegalStateException(); onBackPressed(); }}));
            List<android.net.wifi.WifiConfiguration> saved=wifi.getConfiguredNetworks(); if(saved!=null) for(final android.net.wifi.WifiConfiguration network:saved) r.add(row("Connect: "+network.SSID,new Runnable(){ public void run(){ wifi.enableNetwork(network.networkId,true); wifi.reconnect(); onBackPressed(); }}));
            r.add(row("Back",new Runnable(){ public void run(){ onBackPressed(); }})); return r;
        }});
    }
    private void equalizerMenu() {
        final Equalizer probe=new Equalizer(0,0);
        final int count=probe.getNumberOfPresets(),bands=probe.getNumberOfBands(); final short[] limits=probe.getBandLevelRange();
        final String[] names=new String[count]; final int[] frequencies=new int[bands];
        try { for(int n=0;n<count;n++) names[n]=probe.getPresetName((short)n); for(int n=0;n<bands;n++) frequencies[n]=probe.getCenterFreq((short)n)/1000; } finally { probe.release(); }
        show(new Menu("Equalizer") { List<Item> items(){ List<Item> r=new ArrayList<Item>();
            r.add(row((prefs.number("eq_preset",-1)==-1?"✓ ":"")+"Off",new Runnable(){ public void run(){ prefs.setNumber("eq_preset",-1); changed(); onBackPressed(); }}));
            for(int n=0;n<names.length;n++){ final int index=n; r.add(row((prefs.number("eq_preset",-1)==n?"✓ ":"")+names[n],new Runnable(){ public void run(){ prefs.setNumber("eq_preset",index); changed(); onBackPressed(); }})); }
            r.add(row("Custom",new Runnable(){ public void run(){ show(new Menu("Custom EQ") { List<Item> items(){ List<Item> r=new ArrayList<Item>();
                for(int n=0;n<bands;n++){ final int band=n; r.add(row(frequencies[n]+" Hz: "+prefs.number("eq_band_"+n,0)+" dB",new Runnable(){ public void run(){ numbers(frequencies[band]+" Hz",limits[0]/100,limits[1]/100,prefs.number("eq_band_"+band,0),new Choice(){ public void apply(int value){ prefs.setNumber("eq_band_"+band,value); prefs.setNumber("eq_preset",-2); changed(); }}); }})); }
                r.add(row("Use custom EQ",new Runnable(){ public void run(){ prefs.setNumber("eq_preset",-2); changed(); onBackPressed(); }})); r.add(row("Back",new Runnable(){ public void run(){ onBackPressed(); }})); return r;
            }}); }})); r.add(row("Back",new Runnable(){ public void run(){ onBackPressed(); }})); return r;
        }});
    }
    private void clockMenu() {
        final Calendar edit=Calendar.getInstance();
        show(new Menu("Date / time") { List<Item> items(){
            note=android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",edit).toString()+" · edits apply with Save";
            List<Item> r=new ArrayList<Item>();
            String[] labels={"Year","Month","Day","Hour","Minute"}; final int[] fields={Calendar.YEAR,Calendar.MONTH,Calendar.DAY_OF_MONTH,Calendar.HOUR_OF_DAY,Calendar.MINUTE};
            final int[] minima={2024,1,1,0,0}; final int[] maxima={2040,12,edit.getActualMaximum(Calendar.DAY_OF_MONTH),23,59};
            for(int n=0;n<fields.length;n++){ final int index=n; r.add(row(labels[n]+": "+(edit.get(fields[n])+(n==1?1:0)),new Runnable(){ public void run(){ numbers(new String[]{"Year","Month","Day","Hour","Minute"}[index],minima[index],maxima[index],edit.get(fields[index])+(index==1?1:0),new Choice(){ public void apply(int value){ if(index==0 || index==1) { int day=edit.get(Calendar.DAY_OF_MONTH); edit.set(Calendar.DAY_OF_MONTH,1); edit.set(fields[index],value-(index==1?1:0)); edit.set(Calendar.DAY_OF_MONTH,Math.min(day,edit.getActualMaximum(Calendar.DAY_OF_MONTH))); } else edit.set(fields[index],value); }}); }})); }
            r.add(row("Save date / time",new Runnable(){ public void run(){ edit.set(Calendar.SECOND,0); Settings.Global.putInt(getContentResolver(),Settings.Global.AUTO_TIME,0); ((AlarmManager)getSystemService(ALARM_SERVICE)).setTime(edit.getTimeInMillis()); if(Math.abs(System.currentTimeMillis()-edit.getTimeInMillis())>2000) throw new IllegalStateException(); onBackPressed(); }}));
            r.add(row("Automatic time",new Runnable(){ public void run(){ choose("Automatic time",new String[]{"Off","On"},Settings.Global.getInt(getContentResolver(),Settings.Global.AUTO_TIME,1),new Choice(){ public void apply(int n){ Settings.Global.putInt(getContentResolver(),Settings.Global.AUTO_TIME,n); }}); }}));
            r.add(row("Clock format",new Runnable(){ public void run(){ choose("Clock format",new String[]{"12 hour","24 hour"},android.text.format.DateFormat.is24HourFormat(SettingsActivity.this)?1:0,new Choice(){ public void apply(int n){ Settings.System.putString(getContentResolver(),Settings.System.TIME_12_24,n==1?"24":"12"); }}); }}));
            r.add(row("Time zone (UTC offset)",new Runnable(){ public void run(){ numbers("UTC offset",-12,14,java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())/3600000,new Choice(){ public void apply(int n){ String zone=n==0?"Etc/UTC":"Etc/GMT"+(n>0?"-":"+")+Math.abs(n); Settings.Global.putInt(getContentResolver(),Settings.Global.AUTO_TIME_ZONE,0); ((AlarmManager)getSystemService(ALARM_SERVICE)).setTimeZone(zone); edit.setTimeZone(java.util.TimeZone.getTimeZone(zone)); }}); }}));
            r.add(row("Back",new Runnable(){ public void run(){ onBackPressed(); }})); return r;
        }});
    }
    private void wallpaperMenu(final File directory) {
        show(new Menu("Wallpaper · "+directory.getName()) { List<Item> items(){ List<Item> r=new ArrayList<Item>();
            note="Choose a JPG/PNG on the SD card. Centre: open or apply.";
            r.add(row("No wallpaper",new Runnable(){ public void run(){ prefs.setText("wallpaper",""); onBackPressed(); }}));
            File[] files=directory.listFiles(); if(files!=null) { Arrays.sort(files,new Comparator<File>() { public int compare(File a,File b){ if(a.isDirectory()!=b.isDirectory()) return a.isDirectory()?-1:1; return a.getName().compareToIgnoreCase(b.getName()); }});
                int count=0; for(final File file:files) { if(file.getName().startsWith(".")) continue; String name=file.getName().toLowerCase(java.util.Locale.US); if(!file.isDirectory() && !(name.endsWith(".png")||name.endsWith(".jpg")||name.endsWith(".jpeg"))) continue; if(++count>1000) break;
                    r.add(row((file.isDirectory()?"[Folder] ":"")+file.getName(),new Runnable(){ public void run(){ if(file.isDirectory()) wallpaperMenu(file); else { prefs.setText("wallpaper",file.getAbsolutePath()); onBackPressed(); } }}));
                }
            }
            r.add(row("Back",new Runnable(){ public void run(){ onBackPressed(); }})); return r;
        }});
    }
}
