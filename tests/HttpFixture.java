import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** A loopback HTTP/1.1 fixture using only Java APIs available to Android's unit compiler. */
final class HttpFixture {
    interface Handler {void handle(Exchange exchange)throws IOException;}
    static final class Headers {
        final Map<String,String> values=new LinkedHashMap<String,String>();
        void set(String name,String value){values.put(name,value);}
    }
    static final class Exchange {
        final Socket socket;final URI uri;final Headers headers=new Headers();
        Exchange(Socket socket,URI uri){this.socket=socket;this.uri=uri;}
        URI getRequestURI(){return uri;}
        Headers getResponseHeaders(){return headers;}
        OutputStream getResponseBody()throws IOException{return socket.getOutputStream();}
        void sendResponseHeaders(int status,long length)throws IOException {
            StringBuilder response=new StringBuilder("HTTP/1.1 ").append(status).append(" Fixture\r\nConnection: close\r\nContent-Length: ").append(length).append("\r\n");
            for(Map.Entry<String,String> header:headers.values.entrySet())response.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            getResponseBody().write(response.append("\r\n").toString().getBytes("UTF-8"));
        }
        void close()throws IOException{socket.close();}
    }
    private final ServerSocket server=new ServerSocket();
    private volatile boolean running;
    private Handler handler;private String prefix;private Thread worker;
    HttpFixture(InetSocketAddress address)throws IOException{server.bind(address);}
    void createContext(String prefix,Handler handler){this.prefix=prefix;this.handler=handler;}
    InetSocketAddress getAddress(){return (InetSocketAddress)server.getLocalSocketAddress();}
    void start(){running=true;worker=new Thread(new Runnable(){public void run(){
        while(running){
            Socket socket=null;
            try{
                socket=server.accept();socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
                String line=reader.readLine();if(line==null)continue;String[] request=line.split(" ");if(request.length<2)continue;
                URI uri=URI.create(request[1]);while((line=reader.readLine())!=null && !line.isEmpty()){}
                if(uri.getPath().startsWith(prefix))handler.handle(new Exchange(socket,uri));
            }catch(IOException closed){/* Size limits/cancellation intentionally close client sockets early. */}
            finally{if(socket!=null)try{socket.close();}catch(IOException ignored){}}
        }
    }},"OnLoopio-HTTP-fixture");worker.setDaemon(true);worker.start();}
    void stop(int delay)throws IOException{running=false;server.close();try{worker.join(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
