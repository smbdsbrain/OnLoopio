package io.onloopio.api;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.regex.*;
/** Range is safe only with a strong validator identifying the same original representation. */
public final class ResumableDownload {
    public static final class State {public String validator="";public long offset,total=-1;}
    public interface Source{HttpURLConnection open()throws IOException;}
    public interface Journal{void save(State state)throws IOException;}
    public static boolean strong(String value){if(value==null || value.length()<2 || value.length()>1024 || !value.startsWith("\"") || !value.endsWith("\""))return false;for(int n=1;n<value.length()-1;n++){char ch=value.charAt(n);if(ch<33 || ch=='"' || ch==127 || ch>255)return false;}return true;}

    public static long rangeTotal(String header,long offset,long length)throws IOException{
        Matcher m=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(header==null?"":header);if(!m.matches())throw new IOException("Invalid Content-Range");try{long start=Long.parseLong(m.group(1)),end=Long.parseLong(m.group(2)),total=Long.parseLong(m.group(3));if(start!=offset || end<start || end>=total || length<0 || end-start+1!=length)throw new IOException("Inconsistent Content-Range");return total;}catch(NumberFormatException invalid){throw new IOException("Invalid Content-Range");}
    }
    public static void transfer(Source source,File file,long maximum,State state,Journal journal,NavidromeClient.DownloadProgress progress)throws IOException{
        if(state.offset<0 || state.offset>maximum || !file.isFile() || file.length()<state.offset || !strong(state.validator)){state.offset=0;state.validator="";state.total=-1;}
        RandomAccessFile output=new RandomAccessFile(file,"rw");output.setLength(state.offset);output.close();
        for(int retry=0;retry<2;retry++){
            HttpURLConnection c=source.open();InputStream in=null;FileOutputStream out=null;long received=state.offset;boolean append=received>0;String validator=state.validator;
            try{
                if(append){c.setRequestProperty("Range","bytes="+received+"-");c.setRequestProperty("If-Range",validator);}
                int status=c.getResponseCode();String type=c.getContentType();if(type!=null && (type.contains("xml") || type.contains("json") || type.startsWith("text/")))throw new IOException("Server returned an audio error");
                if(status==416){state.offset=0;state.validator="";state.total=-1;new FileOutputStream(file).close();journal.save(state);continue;}
                if(status!=200 && status!=206)throw new IOException("Audio download failed");long length=c.getContentLength();String etag=c.getHeaderField("ETag");
                if(status==206){long total=rangeTotal(c.getHeaderField("Content-Range"),received,length);if(!append || !validator.equals(etag) || state.total>=0 && state.total!=total){state.offset=0;state.validator="";state.total=-1;new FileOutputStream(file).close();journal.save(state);continue;}state.total=total;}
                else {received=0;append=false;state.offset=0;state.total=length;state.validator=strong(etag)?etag:"";}
                if(state.total>maximum)throw new IOException("Audio file exceeds limit");
                in=c.getInputStream();out=new FileOutputStream(file,append);byte[] buffer=new byte[32768];int count;long committed=received;
                while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");if(received+count>maximum)throw new IOException("Audio file exceeds limit");out.write(buffer,0,count);received+=count;
                    if(received-committed>=1024*1024){out.getFD().sync();state.offset=received;journal.save(state);committed=received;}if(progress!=null)progress.bytes(received,state.total);
                }
                out.getFD().sync();state.offset=received;journal.save(state);
                if(received<16 || state.total>=0 && received!=state.total)throw new IOException("Incomplete audio download");return;
            }finally{
                try{if(out!=null){try{out.getFD().sync();state.offset=file.length();journal.save(state);}finally{out.close();}}}
                finally{try{if(in!=null)in.close();}finally{c.disconnect();}}
            }
        }
        throw new IOException("Range representation changed; retry from zero");
    }
}
