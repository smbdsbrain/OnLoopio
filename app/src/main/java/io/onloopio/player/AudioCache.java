package io.onloopio.player;

import android.content.Context;
import android.os.StatFs;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import io.onloopio.model.Song;
import io.onloopio.model.CacheKey;
import io.onloopio.library.MusicPaths;
import io.onloopio.library.AudioFileIndex;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

/** Public music names; only privately registered downloads belong to cache cleanup. */
public final class AudioCache {
    private static final java.util.concurrent.atomic.AtomicInteger transfers=new java.util.concurrent.atomic.AtomicInteger();
    public static int activeTransfers(){return transfers.get();}
    private final Context context;private final File root,legacy,staging;private final String account;
    private final Set<String> completed=new HashSet<String>();private final Map<String,File> paths=new HashMap<String,File>();
    private long refreshedAt;
    public AudioCache(Context c,ServerConfig config)throws IOException {
        context=c.getApplicationContext();File external=c.getExternalFilesDir(null);if(external==null)throw new IOException("Insert an SD card for offline music.");
        account=CacheKey.hash(config.accountKey());root=MusicPaths.root();legacy=new File(new File(external,"audio"),account);staging=new File(new File(external,"downloads"),account);
        if(!root.isDirectory() && !root.mkdirs())throw new IOException("Music storage unavailable (USB storage mode?).");
        if(!legacy.isDirectory() && !legacy.mkdirs())throw new IOException("Legacy storage unavailable");refresh();
    }
    public File directory(){return root;}
    public File legacyDirectory(){return legacy;}
    public List<String> legacyNames(){List<String> names=new java.util.ArrayList<String>();File[] files=legacy.listFiles();if(files!=null)for(File f:files)if(f.isFile() && f.length()>=16 && f.getName().matches("[0-9a-f]{64}\\.audio"))names.add(f.getName());return names;}
    public synchronized List<String> completedNames(){return new java.util.ArrayList<String>(completed);}
    public long freeBytes(){StatFs disk=new StatFs(root.getPath());return (long)disk.getAvailableBlocks()*disk.getBlockSize();}
    /** Bumped when a refresh changes the set of saved audio. */
    public static volatile int changes;
    public synchronized void refresh(){
        java.util.Set<String> before=new java.util.HashSet<String>(completed);completed.clear();paths.clear();AudioFileIndex index=new AudioFileIndex(context);
        try{for(AudioFileIndex.Entry e:index.entries(account)){File f=new File(e.path);try{MusicPaths.resolve(root,relative(f));if(e.matches(f)){completed.add(e.token);paths.put(e.token,f);}}catch(IOException outside){}}}
        finally{index.close();}
        File[] old=legacy.listFiles();if(old!=null)for(File file:old)if(file.isFile() && file.getName().matches("[0-9a-f]{64}\\.audio") && file.length()>=16 && !paths.containsKey(file.getName())){completed.add(file.getName());paths.put(file.getName(),file);}
        if(!before.equals(completed))changes++;refreshedAt=android.os.SystemClock.uptimeMillis();
    }
    public synchronized void refreshIfStale(){if(android.os.SystemClock.uptimeMillis()-refreshedAt>=2500)refresh();}
    private String relative(File file)throws IOException {String base=root.getCanonicalPath()+File.separator,path=file.getCanonicalPath();if(!path.startsWith(base))throw new IOException("File outside Music");return path.substring(base.length());}
    public synchronized File file(String id)throws IOException {String token=CacheKey.audioName(id);File found=paths.get(token);return found!=null?found:new File(legacy,token);}
    public synchronized boolean contains(String id){return completed.contains(CacheKey.audioName(id));}
    public boolean contains(Song song){
        if(song.local())return new File(song.localPath).isFile();
        if(!contains(song.id))return false;if(originalSupported(song))return true;
        try{return mp3File(file(song.id));}catch(IOException bad){return false;}
    }
    public synchronized long completedBytes(){long total=0;for(File file:paths.values())total+=file.length();return total;}
    public long bytes(){return completedBytes();}
    public boolean remove(String id)throws IOException {return removeToken(CacheKey.audioName(id));}
    private void prune(File dir)throws IOException {String base=root.getCanonicalPath()+File.separator;while(dir!=null && dir.getCanonicalPath().startsWith(base)){if(!dir.delete())break;dir=dir.getParentFile();}}
    public void clear()throws IOException {
        Set<String> tokens=new HashSet<String>();AudioFileIndex index=new AudioFileIndex(context);try{for(AudioFileIndex.Entry e:index.entries(account))tokens.add(e.token);}finally{index.close();}
        File[] old=legacy.listFiles();if(old!=null)for(File f:old)if(f.getName().matches("[0-9a-f]{64}\\.audio"))tokens.add(f.getName());
        for(String token:tokens)removeToken(token);refresh();
    }
    private boolean removeToken(String token)throws IOException {synchronized(AudioFileIndex.IO){
        boolean deleted=false;AudioFileIndex index=new AudioFileIndex(context);
        try{for(AudioFileIndex.Entry e:index.entries(account))if(e.token.equals(token)){File f=new File(e.path);MusicPaths.resolve(root,relative(f));if(e.matches(f)){if(!f.delete())throw new IOException("Cannot remove downloaded music");deleted=true;prune(f.getParentFile());}index.forget(account,token);break;}}
        finally{index.close();}
        File old=new File(legacy,token);if(old.isFile()){if(!old.delete())throw new IOException("Cannot remove legacy audio");deleted=true;}refresh();return deleted;
    }}
    private File destination(Song song,String extension,String token,AudioFileIndex index)throws IOException {
        for(AudioFileIndex.Entry e:index.entries(account))if(e.token.equals(token)){File f=new File(e.path);MusicPaths.resolve(root,relative(f));if(e.matches(f) || !f.exists()){makeParent(f);return f;}}
        String rel=MusicPaths.relative(song,extension);File chosen=MusicPaths.resolve(root,rel);String stem=rel.substring(0,rel.lastIndexOf('.')),tag=CacheKey.hash(account+token).substring(0,10);
        for(int n=0;chosen.exists() || index.reserved(chosen.getCanonicalPath());n++){if(n>=1000)throw new IOException("Too many matching music names");chosen=MusicPaths.resolve(root,stem+" ["+tag+(n==0?"":"-"+n)+"]."+extension);}
        makeParent(chosen);return chosen;
    }
    private void makeParent(File file)throws IOException {File dir=file.getParentFile();if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("Cannot create artist/album folder");}
    /** Registration before same-volume rename makes interruption recoverable from either path. */
    public int migrate(List<Song> songs)throws IOException {
        int moved=0;for(Song song:songs){if(song.local())continue;String token=CacheKey.audioName(song.id);File old=new File(legacy,token);if(!old.isFile() || old.length()<16)continue;
            synchronized(AudioFileIndex.IO){AudioFileIndex index=new AudioFileIndex(context);try{
                File target=destination(song,audioExtension(old,song.suffix),token,index);if(target.exists())continue;
                index.record(account,token,target.getCanonicalPath(),old.length(),old.lastModified());
                if(!old.renameTo(target))throw new IOException("Cannot move downloaded music; original retained");moved++;
            }finally{index.close();}}
        }refresh();return moved;
    }
    private static boolean originalSupported(Song song){return "mp3".equalsIgnoreCase(song.suffix)||"flac".equalsIgnoreCase(song.suffix);}
    private static boolean mp3File(File file)throws IOException {return "mp3".equals(audioExtension(file,""));}
    public static String audioExtension(File file,String fallback)throws IOException {
        byte[] magic=new byte[16];int n;FileInputStream in=new FileInputStream(file);try{n=in.read(magic);}finally{in.close();}String text=new String(magic,0,Math.max(0,n),"ISO-8859-1");
        if(text.startsWith("ID3") || n>=2 && (magic[0]&255)==255 && (magic[1]&224)==224 && (magic[1]&6)!=0)return "mp3";
        if(text.startsWith("fLaC"))return "flac";if(text.startsWith("RIFF"))return "wav";if(text.startsWith("OggS"))return "ogg";if(n>=8 && text.substring(4,8).equals("ftyp"))return "m4a";
        return fallback!=null && fallback.toLowerCase(java.util.Locale.US).matches("mp3|flac|m4a|aac|ogg|wav|ape|wma")?fallback.toLowerCase(java.util.Locale.US):"audio";
    }
    public File obtain(Song song,NavidromeClient client,NavidromeClient.DownloadProgress listener)throws IOException {return obtain(song,client,listener,!originalSupported(song));}
    public File obtain(String id,NavidromeClient client,NavidromeClient.DownloadProgress listener)throws IOException {return obtain(new Song(id,id,"Unknown artist","Unknown album","mp3",0),client,listener,false);}
    private File obtain(final Song song,NavidromeClient client,final NavidromeClient.DownloadProgress listener,boolean mp3)throws IOException {
        if(song.local())return new File(song.localPath);refresh();if(contains(song))return file(song.id);
        if(!staging.isDirectory() && !staging.mkdirs())throw new IOException("Cannot stage music download");final String token=CacheKey.audioName(song.id);File partial=new File(staging,token+"-"+System.nanoTime()+".part");
        transfers.incrementAndGet();try{
            NavidromeClient.DownloadProgress progress=new NavidromeClient.DownloadProgress(){long lastCheck;public void bytes(long received,long total)throws IOException{if(received-lastCheck>=1024*1024 || lastCheck==0){lastCheck=received;if(freeBytes()<16L*1024*1024)throw new IOException("Not enough SD card space.");}if(listener!=null)listener.bytes(received,total);}};
            if(mp3)client.downloadMp3(song.id,partial,512L*1024*1024,progress);else client.download(song.id,partial,512L*1024*1024,progress);
            String extension=audioExtension(partial,song.suffix);FileInputStream header=new FileInputStream(partial);byte[] bytes=new byte[16];int length;try{length=header.read(bytes);}finally{header.close();}
            String prefix=new String(bytes,0,Math.max(length,0),"ISO-8859-1");
            boolean valid=prefix.startsWith("ID3")||prefix.startsWith("fLaC")||prefix.startsWith("RIFF")||prefix.startsWith("OggS")||prefix.startsWith("MAC ")||prefix.startsWith("wvpk")||prefix.startsWith("DSD ")||prefix.startsWith("FRM8")||prefix.startsWith("FORM")||length>=8 && prefix.substring(4,8).equals("ftyp")||length>=2 && (bytes[0]&255)==255 && (bytes[1]&224)==224;
            if(partial.length()<16 || !valid || "audio".equals(extension) || mp3 && !"mp3".equals(extension))throw new IOException("Unsupported or invalid audio file.");
            synchronized(AudioFileIndex.IO){AudioFileIndex index=new AudioFileIndex(context);try{
                File target=destination(song,extension,token,index);AudioFileIndex.Entry previous=null;for(AudioFileIndex.Entry e:index.entries(account))if(e.token.equals(token))previous=e;
                index.record(account,token,target.getCanonicalPath(),partial.length(),partial.lastModified());
                if(!partial.renameTo(target)){if(previous==null)index.forget(account,token);else index.record(account,token,previous.path,previous.bytes,previous.modified);throw new IOException("Cannot publish downloaded audio");}
                refresh();return target;
            }finally{index.close();}}
        }finally{if(partial.isFile())partial.delete();transfers.decrementAndGet();}
    }
}
