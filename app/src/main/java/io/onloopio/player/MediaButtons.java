package io.onloopio.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public final class MediaButtons extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent) {
        KeyEvent event=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if(event!=null && handle(context,event) && isOrderedBroadcast())abortBroadcast();
    }
    public static boolean handle(Context context,KeyEvent event){
        int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){
            if(io.onloopio.device.ControlLock.locked(context))return true;
            context.startService(new Intent(context,PlaybackService.class).setAction(PlaybackService.MEDIA_KEY).putExtra("key",event));return true;
        }
        if(code!=KeyEvent.KEYCODE_MEDIA_PLAY && code!=KeyEvent.KEYCODE_MEDIA_PAUSE && code!=KeyEvent.KEYCODE_MEDIA_NEXT && code!=KeyEvent.KEYCODE_MEDIA_PREVIOUS)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)handle(context,code);return true;
    }
    /** Explicit commands remain immediate; hardware events use the gesture overload. */
    public static boolean handle(Context context,int code) {
        if(io.onloopio.device.ControlLock.locked(context) && io.onloopio.device.ControlLock.media(code))return true;
        String action=code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? PlaybackService.TOGGLE :
                code==KeyEvent.KEYCODE_MEDIA_PLAY ? PlaybackService.RESUME : code==KeyEvent.KEYCODE_MEDIA_PAUSE ? PlaybackService.PAUSE :
                code==KeyEvent.KEYCODE_MEDIA_NEXT ? PlaybackService.NEXT : code==KeyEvent.KEYCODE_MEDIA_PREVIOUS ? PlaybackService.PREVIOUS : null;
        if(action==null) return false; PlaybackService.action(context,action); return true;
    }
}
