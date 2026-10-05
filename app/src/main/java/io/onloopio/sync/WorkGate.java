package io.onloopio.sync;
import android.content.*;
import android.os.BatteryManager;
import io.onloopio.device.*;
public final class WorkGate {
    private static String bucket(String account,WorkPolicy.Work work){return work+"_"+io.onloopio.model.CacheKey.hash(account);}
    private static String bucket(Context c,WorkPolicy.Work work){io.onloopio.api.ServerConfig config=new io.onloopio.config.ConfigStore(c).load();return bucket(config==null?"":config.accountKey(),work);}
    public static String reason(Context c,WorkPolicy.Work work,boolean manual){
        String bucket=bucket(c,work);if(!manual && new DeviceSettings(c).flag("auth_blocked_"+bucket,false))return "Check account settings";
        DeviceSettings s=new DeviceSettings(c);WorkPolicy.Input i=new WorkPolicy.Input();Intent battery=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery!=null){int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);i.battery=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)*100/Math.max(1,scale);i.charging=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;}
        boolean old=s.flag("large_work_low",false);i.low=WorkPolicy.low(old,i.battery);if(old!=i.low)s.setFlag("large_work_low",i.low);
        i.offline=s.flag("force_offline",false);i.usb=s.flag("usb_quiescing",false) || !android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState());i.home=new OnlineMode(c).homeWifi();i.manual=manual;i.chargeProfile=s.number("energy_profile",0)==1;i.enabled=work==WorkPolicy.Work.FEEDBACK?s.flag("feedback_auto_sync",true):s.flag("playlist_auto_sync",true);if(work==WorkPolicy.Work.DOWNLOAD)i.enabled=!s.flag("downloads_paused",false);
        i.now=System.currentTimeMillis();long saved;try{saved=Long.parseLong(s.text("retry_"+bucket,"0"));}catch(NumberFormatException invalid){saved=0;}i.next=WorkPolicy.boundedNext(i.now,saved);if(saved!=0 && saved!=i.next)s.setText("retry_"+bucket,Long.toString(i.next));return WorkPolicy.reason(i,work);
    }
    public static void networkReturned(Context c){DeviceSettings s=new DeviceSettings(c);for(WorkPolicy.Work work:WorkPolicy.Work.values()){String key=bucket(c,work);if(!s.flag("auth_blocked_"+key,false))s.setText("retry_"+key,"0");}}
    public static void result(Context c,WorkPolicy.Work work,boolean success,boolean auth){io.onloopio.api.ServerConfig config=new io.onloopio.config.ConfigStore(c).load();result(c,config==null?"":config.accountKey(),work,success,auth);}
    public static void result(Context c,String account,WorkPolicy.Work work,boolean success,boolean auth){String bucket=bucket(account,work);DeviceSettings s=new DeviceSettings(c);int failures=success?0:Math.min(100,s.number("failures_"+bucket,0)+1);s.setNumber("failures_"+bucket,failures);s.setText("retry_"+bucket,Long.toString(success?0:auth?Long.MAX_VALUE:WorkPolicy.retryAt(System.currentTimeMillis(),failures,new java.util.Random().nextDouble())));s.setFlag("auth_blocked_"+bucket,auth);SyncScheduler.ensure(c,false);}
}
