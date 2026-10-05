package io.onloopio.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import io.onloopio.device.DeviceSettings;
import io.onloopio.device.Y1Keys;
import io.onloopio.device.ControlLock;
import io.onloopio.device.CenterGesture;
import java.util.ArrayList;
import java.util.List;

/** Shared wheel navigation: choices and confirmations never require text entry. */
abstract class WheelActivity extends Activity {
    static final class Item {
        final String label,key; final Runnable action; final Integer swatchColor;
        Item(String label,Runnable action) { this(label,action,null); }
        Item(String label,Runnable action,Integer swatchColor) { this(label,action,swatchColor,label); }
        Item(String label,Runnable action,Integer swatchColor,String key) { this.label=label; this.action=action; this.swatchColor=swatchColor;this.key=key; }
    }
    abstract static class Menu {
        final String title; String note="Wheel: choose · Centre: apply · Back: cancel",selectedRow; int selected,top;
        Menu(String title) { this.title=title; }
        abstract List<Item> items();
    }
    final List<Menu> stack=new ArrayList<Menu>();
    ListView list; TextView status; List<Item> rows;
    DeviceSettings prefs;
    private CenterGesture center;private Menu centerMenu;private Item centerItem;
    abstract Menu rootMenu();
    public void onUserInteraction(){super.onUserInteraction();io.onloopio.device.IdleScheduler.activity(this);}
    public void onCreate(Bundle state) {
        super.onCreate(state); prefs=new DeviceSettings(this); stack.add(rootMenu());
        center=new CenterGesture(this,new android.os.Handler(),new CenterGesture.Listener(){
            public void started(){centerMenu=stack.get(stack.size()-1);int position=Math.max(0,list.getSelectedItemPosition());centerItem=position<rows.size()?rows.get(position):null;}
            public void shortPress(int count){if(stack.get(stack.size()-1)==centerMenu && centerItem!=null){prefs.feedback();activateItem(centerItem);}}
            public void longPress(){}
            public void lockChanged(boolean locked){prefs.feedback();returnToPlayer();}
        });render();
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }
    protected void onResume() { super.onResume(); if(ControlLock.locked(this)){returnToPlayer();return;}if(!stack.isEmpty()) render(); }
    protected void onPause(){remember();center.cancel();super.onPause();}
    private void returnToPlayer(){startActivity(new android.content.Intent(this,PlaylistActivity.class).setAction("io.onloopio.OPEN_PLAYER").addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));}
    void show(Menu menu) { remember(); stack.add(menu); render(); }
    void remember() {
        if(list==null || stack.isEmpty())return;int selected=list.getSelectedItemPosition();if(selected<0)return;
        Menu menu=stack.get(stack.size()-1);menu.selected=selected;
        if(selected<rows.size())menu.selectedRow=rows.get(selected).key;
        View row=list.getChildAt(selected-list.getFirstVisiblePosition());menu.top=row==null?0:row.getTop()-list.getPaddingTop();
    }
    void render() {
        Menu menu=stack.get(stack.size()-1); rows=menu.items();
        int selected=menu.selected,nearest=-1;
        if(menu.selectedRow!=null)for(int n=0;n<rows.size();n++)if(menu.selectedRow.equals(rows.get(n).key) && (nearest<0 || Math.abs(n-selected)<Math.abs(nearest-selected)))nearest=n;
        if(nearest>=0)selected=nearest;
        LinearLayout page=Ui.page(this); page.addView(Ui.title(this,menu.title));
        status=Ui.text(this,menu.note,11,Ui.FG); page.addView(status);
        list=new ListView(this); list.setSoundEffectsEnabled(false); list.setCacheColorHint(Ui.BG); list.setDividerHeight(1);
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int p) { return rows.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p,View old,ViewGroup parent) {
                TextView view=old instanceof TextView?(TextView)old:Ui.row(WheelActivity.this,"");Item item=rows.get(p);view.setText(item.label);
                android.graphics.drawable.GradientDrawable swatch=null;
                if(item.swatchColor!=null){swatch=new android.graphics.drawable.GradientDrawable();swatch.setColor(item.swatchColor);swatch.setCornerRadius(Ui.dp(WheelActivity.this,4));swatch.setStroke(Ui.dp(WheelActivity.this,1),Ui.TRACK);int size=Ui.dp(WheelActivity.this,18);swatch.setBounds(0,0,size,size);}
                view.setCompoundDrawablePadding(Ui.dp(WheelActivity.this,10));view.setCompoundDrawables(swatch,null,null,null);return view;
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() { public void onItemClick(AdapterView<?> parent,View view,int position,long id) { activate(position); } });
        page.addView(list,new LinearLayout.LayoutParams(-1,0,1)); setContentView(page);
        list.requestFocus();list.setSelectionFromTop(Math.min(selected,Math.max(0,rows.size()-1)),menu.top);
    }
    void activate(int position) {
        if(ControlLock.locked(this))return;
        if(position<0 || position>=rows.size()) return;if(list.getSelectedItemPosition()!=position)list.setSelection(position);activateItem(rows.get(position));
    }
    private void activateItem(Item item) {
        if(ControlLock.locked(this))return;remember();
        try { item.action.run(); }
        catch(Exception failure) { status.setText("Unavailable on this firmware. Use the USB maintenance script."); android.util.Log.w("OnLoopio","DEVICE_SETTING_UNAVAILABLE"); }
    }
    public void onBackPressed() { if(ControlLock.locked(this))return;center.cancel();if(stack.size()>1) { stack.remove(stack.size()-1); render(); } else finish(); }
    void confirm(final String title,final Runnable apply) {
        show(new Menu(title) { List<Item> items() { List<Item> result=new ArrayList<Item>(); result.add(new Item("Cancel",new Runnable(){ public void run(){ onBackPressed(); }})); result.add(new Item("Confirm",new Runnable(){ public void run(){ apply.run(); onBackPressed(); }})); return result; }});
    }
    interface Choice { void apply(int index); }
    void choose(final String title,final String[] labels,int selected,final Choice change) {
        Menu menu=new Menu(title) { List<Item> items() { List<Item> result=new ArrayList<Item>(); for(int n=0;n<labels.length;n++){ final int value=n; result.add(new Item(labels[n],new Runnable(){ public void run(){ change.apply(value); onBackPressed(); }})); } return result; }};
        menu.selected=Math.max(0,selected); show(menu);
    }
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code=event.getKeyCode();
        if(Y1Keys.select(code)){center.key(event);return true;}
        if(ControlLock.locked(this) && ControlLock.blocks(code)){center.cancel();return true;}
        center.cancel();
        if(event.getAction()==KeyEvent.ACTION_DOWN && (Y1Keys.previousRow(code)||Y1Keys.nextRow(code))) prefs.feedback();
        if(Y1Keys.previousRow(code)||Y1Keys.nextRow(code)) return super.dispatchKeyEvent(new KeyEvent(event.getDownTime(),event.getEventTime(),event.getAction(),Y1Keys.previousRow(code)?KeyEvent.KEYCODE_DPAD_UP:KeyEvent.KEYCODE_DPAD_DOWN,event.getRepeatCount()));
        return super.dispatchKeyEvent(event);
    }
    public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused && center!=null)center.cancel();}
}
