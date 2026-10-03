package io.onloopio.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** API-17 paired-device discovery and audio routing, controlled entirely by the wheel. */
// The supported API-17 firmware grants the manifest's legacy Bluetooth permissions.
@android.annotation.SuppressLint("MissingPermission")
public final class BluetoothActivity extends WheelActivity {
    private BluetoothAdapter adapter;
    private BluetoothProfile audio;
    private BluetoothDevice pendingPair; private Menu pairingMenu;
    private final Map<String,BluetoothDevice> found=new LinkedHashMap<String,BluetoothDevice>();
    private String message="Centre: select a device. Back: settings.";
    private final BluetoothProfile.ServiceListener profile=new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int type,BluetoothProfile proxy) { audio=proxy; refresh(); }
        public void onServiceDisconnected(int type) { audio=null; refresh(); }
    };
    private final BroadcastReceiver events=new BroadcastReceiver() {
        public void onReceive(Context context,Intent intent) {
            BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if("android.bluetooth.device.action.PAIRING_REQUEST".equals(intent.getAction()) && device!=null) {
                if(pairing(device,intent.getIntExtra("android.bluetooth.device.extra.PAIRING_VARIANT",-1),intent.getIntExtra("android.bluetooth.device.extra.PAIRING_KEY",0)) && isOrderedBroadcast()) abortBroadcast();
                return;
            }
            if(device!=null && BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) found.put(device.getAddress(),device);
            if(device!=null && BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) message=device.getBondState()==BluetoothDevice.BOND_BONDED?"Paired. Select Connect audio.":"Pairing state changed.";
            if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) message="Search complete.";
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()) && adapter.isEnabled() && audio==null) adapter.getProfileProxy(BluetoothActivity.this,profile,BluetoothProfile.A2DP);
            refresh();
        }
    };
    Menu rootMenu() {
        adapter=BluetoothAdapter.getDefaultAdapter();
        return new Menu("Bluetooth") { List<Item> items(){
            List<Item> result=new ArrayList<Item>(); note=message;
            if(adapter==null) { result.add(new Item("Bluetooth is unavailable",new Runnable(){ public void run(){ onBackPressed(); }})); return result; }
            result.add(new Item(adapter.isEnabled()?"Turn Bluetooth off":"Turn Bluetooth on",new Runnable(){ public void run(){ boolean okay=adapter.isEnabled()?adapter.disable():adapter.enable(); message=okay?"Changing Bluetooth state…":"Cannot change Bluetooth state."; refresh(); }}));
            if(adapter.isEnabled()) {
                result.add(new Item(adapter.isDiscovering()?"Stop searching":"Search for devices",new Runnable(){ public void run(){ if(adapter.isDiscovering()) adapter.cancelDiscovery(); else { found.clear(); adapter.startDiscovery(); } refresh(); }}));
                for(BluetoothDevice device:adapter.getBondedDevices()) found.put(device.getAddress(),device);
                for(final BluetoothDevice device:found.values()) {
                    int state=audio==null?BluetoothProfile.STATE_DISCONNECTED:audio.getConnectionState(device);
                    String suffix=state==BluetoothProfile.STATE_CONNECTED?" · connected":state==BluetoothProfile.STATE_CONNECTING?" · connecting":device.getBondState()==BluetoothDevice.BOND_BONDED?" · paired":device.getBondState()==BluetoothDevice.BOND_BONDING?" · pairing":"";
                    result.add(new Item(name(device)+suffix,new Runnable(){ public void run(){ deviceMenu(device); }}));
                }
            }
            result.add(new Item("Back to settings",new Runnable(){ public void run(){ finish(); }})); return result;
        }};
    }
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state); if(adapter==null) return;
        IntentFilter filter=new IntentFilter(); filter.addAction(BluetoothDevice.ACTION_FOUND); filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED); filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"); filter.addAction("android.bluetooth.device.action.PAIRING_REQUEST"); filter.setPriority(2000);
        registerReceiver(events,filter); if(adapter.isEnabled()) adapter.getProfileProxy(this,profile,BluetoothProfile.A2DP);
    }
    private String name(BluetoothDevice device) { return device.getName()==null?device.getAddress():device.getName(); }
    private void refresh() { if(list!=null && !isFinishing()) { remember(); render(); } }
    private boolean call(Object target,String method,Class<?>[] types,Object[] values) {
        try { Object result=target.getClass().getMethod(method,types).invoke(target,values); return !(result instanceof Boolean) || ((Boolean)result).booleanValue(); }
        catch(Exception unavailable) { message="Bluetooth action unavailable on this firmware."; return false; }
    }
    private boolean bond(BluetoothDevice device,String method) { return call(device,method,new Class<?>[0],new Object[0]); }
    private void deviceMenu(final BluetoothDevice device) {
        show(new Menu(name(device)) { List<Item> items(){ List<Item> result=new ArrayList<Item>(); note=device.getAddress()+"\n"+message;
            if(device.getBondState()!=BluetoothDevice.BOND_BONDED) result.add(new Item("Pair device",new Runnable(){ public void run(){ adapter.cancelDiscovery(); message=bond(device,"createBond")?"Waiting for pairing…":"Pairing could not start."; refresh(); }}));
            else {
                result.add(new Item("Connect audio",new Runnable(){ public void run(){ adapter.cancelDiscovery(); if(audio==null) message="Audio profile is starting. Try again."; else message=call(audio,"connect",new Class<?>[]{BluetoothDevice.class},new Object[]{device})?"Connecting audio…":"Connection did not start."; refresh(); }}));
                result.add(new Item("Disconnect audio",new Runnable(){ public void run(){ if(audio!=null) call(audio,"disconnect",new Class<?>[]{BluetoothDevice.class},new Object[]{device}); message="Disconnecting audio…"; refresh(); }}));
                result.add(new Item("Forget device",new Runnable(){ public void run(){ confirm("Forget "+name(device)+"?",new Runnable(){ public void run(){ bond(device,"removeBond"); }}); }}));
            }
            result.add(new Item("Back",new Runnable(){ public void run(){ onBackPressed(); }})); return result;
        }});
    }
    private boolean pairing(final BluetoothDevice device,int variant,final int key) {
        if(variant==0 || variant==1) {
            pendingPair=device;
            final boolean numeric=variant==1; final int[] digits={0,0,0,0,0,0}; final int[] length={numeric?6:4};
            pairingMenu=new Menu("Pairing PIN · "+name(device)) { List<Item> items(){ List<Item> result=new ArrayList<Item>(); StringBuilder pin=new StringBuilder(); for(int n=0;n<length[0];n++) pin.append(digits[n]); note="PIN: "+pin+" · Centre edits a digit";
                result.add(new Item("Cancel pairing",new Runnable(){ public void run(){ bond(device,"cancelBondProcess"); onBackPressed(); }}));
                for(int n=0;n<length[0];n++){ final int position=n; result.add(new Item("Digit "+(n+1)+": "+digits[n],new Runnable(){ public void run(){ choose("Digit "+(position+1),new String[]{"0","1","2","3","4","5","6","7","8","9"},digits[position],new Choice(){ public void apply(int value){ digits[position]=value; }}); }})); }
                if(!numeric) result.add(new Item("PIN length: "+length[0],new Runnable(){ public void run(){ choose("PIN length",new String[]{"4 digits","6 digits"},length[0]==4?0:1,new Choice(){ public void apply(int value){ length[0]=value==0?4:6; }}); }}));
                result.add(new Item("Submit PIN",new Runnable(){ public void run(){ StringBuilder pin=new StringBuilder(); for(int n=0;n<length[0];n++) pin.append(digits[n]); boolean okay=numeric?call(device,"setPasskey",new Class<?>[]{int.class},new Object[]{Integer.valueOf(pin.toString())}):call(device,"setPin",new Class<?>[]{byte[].class},new Object[]{pin.toString().getBytes(java.nio.charset.Charset.forName("US-ASCII"))}); if(okay) { pendingPair=null; onBackPressed(); } else refresh(); }})); return result;
            }}; show(pairingMenu); return true;
        }
        if(variant==2 || variant==3 || variant==6) {
            pendingPair=device;
            pairingMenu=new Menu("Pair "+name(device)+(variant==2?" · "+String.format(java.util.Locale.US,"%06d",key):"")+"?") { List<Item> items(){ List<Item> result=new ArrayList<Item>();
                result.add(new Item("Cancel",new Runnable(){ public void run(){ onBackPressed(); }}));
                result.add(new Item("Confirm",new Runnable(){ public void run(){ if(call(device,"setPairingConfirmation",new Class<?>[]{boolean.class},new Object[]{Boolean.TRUE})){ pendingPair=null; onBackPressed(); } }})); return result;
            }}; show(pairingMenu); return true;
        }
        // Display-only or unknown variants retain the platform's specialized pairing prompt.
        return false;
    }
    private void cancelPair() { if(pendingPair!=null){ bond(pendingPair,"cancelBondProcess"); pendingPair=null; } }
    public void onBackPressed() { if(!stack.isEmpty() && stack.get(stack.size()-1)==pairingMenu) cancelPair(); super.onBackPressed(); }
    protected void onDestroy() { cancelPair(); if(adapter!=null){ adapter.cancelDiscovery(); unregisterReceiver(events); if(audio!=null) adapter.closeProfileProxy(BluetoothProfile.A2DP,audio); } super.onDestroy(); }
}
