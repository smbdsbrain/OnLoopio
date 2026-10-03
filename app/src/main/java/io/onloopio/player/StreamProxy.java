package io.onloopio.player;
import android.util.Log;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/** Local-only, authenticated HTTPS-to-HTTP bridge for API 17 audio decoders. */
public final class StreamProxy {
    private final ServerSocket server; private final Thread thread; private final NavidromeClient client;
    private volatile boolean closed; private volatile String songId;
    private volatile boolean transcode;
    private final String token;
    private final Set<Response> responses=new HashSet<Response>();
    private static final class Response {
        final Socket socket;volatile HttpURLConnection remote;
        Response(Socket socket){this.socket=socket;}
        void close(){try{socket.close();}catch(IOException ignored){}HttpURLConnection connection=remote;if(connection!=null)connection.disconnect();}
    }
    public StreamProxy(ServerConfig config) throws IOException {
        byte[] secret=new byte[24]; new SecureRandom().nextBytes(secret); StringBuilder t=new StringBuilder();
        for(byte b:secret) t.append(String.format(java.util.Locale.US,"%02x",b&255)); token=t.toString();
        client=new NavidromeClient(config); server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
        thread=new Thread(new Runnable(){public void run(){ serve(); }},"OnLoopio-stream-proxy"); thread.setDaemon(true); thread.start();
    }
    public String url(String id) { songId=id; return "http://127.0.0.1:"+server.getLocalPort()+"/"+token; }
    public void setTranscode(boolean value) { transcode=value; }
    public void close() {
        Response[] active;synchronized(responses){closed=true;active=responses.toArray(new Response[responses.size()]);}
        try {server.close();}catch(IOException ignored){}
        for(Response response:active)response.close();
    }
    private void serve() {
        while(!closed) {
            try {
                final Response response=new Response(server.accept());
                synchronized(responses){if(closed){response.close();return;}responses.add(response);}
                new Thread(new Runnable(){public void run(){handle(response);}},"OnLoopio-audio-response").start();
            }
            catch(IOException failure) { if(!closed) Log.w("OnLoopio","Stream proxy accept failed"); }
        }
    }
    private static void header(OutputStream out,String text) throws IOException { out.write(text.getBytes("ISO-8859-1")); }
    private void handle(Response response) {
        Socket socket=response.socket;HttpURLConnection remote=null;InputStream audio=null;
        try {
            socket.setSoTimeout(20000); InputStream in=new BufferedInputStream(socket.getInputStream()); OutputStream out=new BufferedOutputStream(socket.getOutputStream());
            String request=line(in); if(request==null) return;
            String[] first=request.split(" "); if(first.length<3 || !("GET".equals(first[0]) || "HEAD".equals(first[0])) || !first[1].equals("/"+token)) { header(out,"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); out.flush(); return; }
            String range=null; for(int n=0;n<64;n++) { String value=line(in); if(value==null || value.length()==0) break; if(value.regionMatches(true,0,"Range: ",0,7)) range=value.substring(7).trim(); }
            String selected=songId; if(selected==null) throw new IOException("No stream selected.");
            remote=client.stream(selected,range,transcode?"mp3":"raw"); int status=remote.getResponseCode();
            response.remote=remote;if(closed)return;
            if(status==200 && range!=null && !"bytes=0-".equals(range)) throw new IOException("Server did not honor audio range.");
            header(out,"HTTP/1.1 "+status+(status==206?" Partial Content":status==416?" Range Not Satisfiable":" OK")+"\r\n");
            String type=remote.getContentType(); if(type!=null) header(out,"Content-Type: "+type+"\r\n");
            String length=remote.getHeaderField("Content-Length"); if(length!=null) header(out,"Content-Length: "+length+"\r\n");
            String contentRange=remote.getHeaderField("Content-Range"); if(contentRange!=null) header(out,"Content-Range: "+contentRange+"\r\n");
            header(out,"Accept-Ranges: bytes\r\nConnection: close\r\n\r\n"); out.flush();
            if(!"HEAD".equals(first[0]) && status!=416) { audio=remote.getInputStream(); byte[] buffer=new byte[32768]; int count; while(!closed && (count=audio.read(buffer))!=-1) { out.write(buffer,0,count); out.flush(); } }
        } catch(IOException failure) { Log.w("OnLoopio","Audio stream interrupted: "+failure.getClass().getSimpleName()); }
        finally {
            if(audio!=null)try{audio.close();}catch(IOException ignored){}
            if(remote!=null)remote.disconnect();try{socket.close();}catch(IOException ignored){}
            synchronized(responses){responses.remove(response);}
        }
    }
    private static String line(InputStream in) throws IOException {
        StringBuilder result=new StringBuilder(); int current; while((current=in.read())!=-1) { if(current=='\n') return result.toString(); if(current!='\r') result.append((char)current); if(result.length()>8192) throw new IOException("HTTP line too long."); }
        return result.length()==0?null:result.toString();
    }
}
