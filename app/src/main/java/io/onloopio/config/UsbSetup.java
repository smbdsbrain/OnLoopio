package io.onloopio.config;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Environment;
import io.onloopio.api.ServerConfig;
import io.onloopio.device.DeviceSettings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import org.json.JSONObject;

/** One-time configuration from the USB-visible card. The input is removed before it is parsed. */
public final class UsbSetup {
    public static final String FILE_NAME="OnLoopio-setup.json";
    private static final int MAX_BYTES=32768;
    private UsbSetup() { }

    public static boolean importFromUsb(Context context) {
        if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))return false;
        File file=new File(Environment.getExternalStorageDirectory(),FILE_NAME);
        if(!file.isFile())return false;
        DeviceSettings settings=new DeviceSettings(context);
        try {
            byte[] content=read(file);
            if(!file.delete())throw new IllegalStateException("Setup file could not be removed");
            JSONObject root=new JSONObject(new String(content,"UTF-8"));
            if(root.getInt("version")!=1)throw new IllegalArgumentException("Unsupported setup version");
            JSONObject network=root.getJSONObject("wifi"),server=root.getJSONObject("server");
            String ssid=network.getString("ssid"),password=network.getString("password");
            if(ssid.length()==0 || ssid.getBytes("UTF-8").length>32 || control(ssid) || control(password)
                    || password.length()!=0 && (password.length()<8 || password.length()>63))
                throw new IllegalArgumentException("Invalid Wi-Fi settings");
            ServerConfig config=new ServerConfig(server.getString("url"),server.getString("username"),
                    server.getString("password"),server.optString("trustedCa",""));
            WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wifi==null)throw new IllegalStateException("Wi-Fi unavailable");
            WifiConfiguration saved=new WifiConfiguration(); saved.SSID=quoted(ssid);
            if(password.length()==0)saved.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            else { saved.preSharedKey=quoted(password);saved.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK); }
            int id=wifi.addNetwork(saved);
            if(id<0 || !wifi.saveConfiguration() || !wifi.setWifiEnabled(true) || !wifi.enableNetwork(id,true))
                throw new IllegalStateException("Could not save or connect Wi-Fi");
            // Wi-Fi may still be turning on; an immediate reconnect failure is transient.
            wifi.reconnect();
            new ConfigStore(context).save(config);
            settings.setText("home_ssid",ssid);
            settings.setText("setup_status","Setup imported · connecting Wi-Fi");
            return true;
        } catch(Exception failure) {
            // The reason and file contents may include credentials. Never log either.
            settings.setText("setup_status","USB setup failed · reconnect to PC and retry");
            return false;
        } finally {
            if(file.exists() && !file.delete())settings.setText("setup_status","USB setup file remains · remove it on PC");
        }
    }

    private static byte[] read(File file) throws Exception {
        if(file.length()<1 || file.length()>MAX_BYTES)throw new IllegalArgumentException("Setup size");
        FileInputStream input=new FileInputStream(file);
        try {
            ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
            while((count=input.read(buffer))!=-1){if(result.size()+count>MAX_BYTES)throw new IllegalArgumentException("Setup size");result.write(buffer,0,count);}
            return result.toByteArray();
        } finally { input.close(); }
    }
    private static boolean control(String text){for(int i=0;i<text.length();i++)if(text.charAt(i)<32)return true;return false;}
    private static String quoted(String text){return "\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
