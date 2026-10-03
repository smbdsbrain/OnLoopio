package io.onloopio.device;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
/** Home network gate. The caller marks a successful/failed server check. */
public final class OnlineMode {
    private final Context context; private final DeviceSettings settings;
    private volatile boolean reachable; private volatile long checkedAt;
    public OnlineMode(Context context) { this.context=context.getApplicationContext(); settings=new DeviceSettings(this.context); }
    public boolean homeWifi() {
        if(!Connectivity.wifiConnected(context)) return false;
        WifiInfo info=((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
        if(info==null) return false; String ssid=info.getSSID();
        String home=settings.text("home_ssid","");
        return home.length()>0 && (home.equals(ssid) || ("\""+home+"\"").equals(ssid));
    }
    public boolean online() { return !settings.flag("force_offline",false) && homeWifi() && reachable && android.os.SystemClock.elapsedRealtime()-checkedAt<60000; }
    public boolean needsCheck() {return android.os.SystemClock.elapsedRealtime()-checkedAt>30000;}
    public boolean check(ServerConfig config) {
        if(config==null || settings.flag("force_offline",false) || !homeWifi()) { reachable=false; return false; }
        try { new NavidromeClient(config).ping(); reachable=true; }
        catch(Exception failure) { reachable=false; }
        checkedAt=android.os.SystemClock.elapsedRealtime(); return online();
    }
    public void failed() { reachable=false; checkedAt=android.os.SystemClock.elapsedRealtime(); }
}
