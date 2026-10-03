package io.onloopio;

import android.util.Base64;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import org.json.JSONObject;
import io.onloopio.api.ServerConfig;

/** Loopback TLS fixture. Keys are generated outside Git and supplied only to the explicit probe. */
final class TlsKeepAliveFixture {
    private final SSLServerSocket server;
    private final Set<Socket> clients=new HashSet<Socket>();
    private final Thread acceptor;
    private volatile boolean closed;
    private final String ca;
    volatile int requests,closeRequests;

    TlsKeepAliveFixture(JSONObject material)throws Exception {
        ca=material.getString("caPem");
        Provider provider=(Provider)Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null);
        CertificateFactory certificates=CertificateFactory.getInstance("X.509");
        Certificate leaf=certificates.generateCertificate(new ByteArrayInputStream(material.getString("certificatePem").getBytes("UTF-8")));
        Certificate anchor=certificates.generateCertificate(new ByteArrayInputStream(ca.getBytes("UTF-8")));
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(material.getString("privateKeyBase64"),Base64.DEFAULT)));
        KeyStore keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null,null);
        char[] password=new char[0];keys.setKeyEntry("fixture",key,password,new Certificate[]{leaf,anchor});
        KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password);
        SSLContext tls=SSLContext.getInstance("TLSv1.2",provider);tls.init(managers.getKeyManagers(),null,null);
        server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        server.setEnabledProtocols(new String[]{"TLSv1.2"});
        acceptor=new Thread(new Runnable(){public void run(){accept();}},"OnLoopio-TLS-fixture");acceptor.start();
    }
    ServerConfig config(){return new ServerConfig("https://127.0.0.1:"+server.getLocalPort(),"fixture","fixture",ca);}
    private void accept(){while(!closed){try{
        final Socket socket=server.accept();synchronized(clients){if(closed){socket.close();return;}clients.add(socket);}
        Thread worker=new Thread(new Runnable(){public void run(){respond(socket);}},"OnLoopio-TLS-response");worker.setDaemon(true);worker.start();
    }catch(IOException failure){if(!closed)throw new IllegalStateException("Fixture accept failed");}}}
    private void respond(Socket socket){try{
        socket.setSoTimeout(30000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),"ISO-8859-1"));OutputStream output=socket.getOutputStream();
        String request;while(!closed && (request=input.readLine())!=null){
            String line;boolean close=false;while((line=input.readLine())!=null && line.length()>0)if("connection: close".equalsIgnoreCase(line.trim()))close=true;
            requests++;if(close)closeRequests++;
            String path=request.split(" ")[1];boolean error=path.contains("id=error"),truncated=path.contains("id=truncated"),slow=path.contains("id=slow");
            boolean binary=path.contains("/download.view") || path.contains("/stream.view") || path.contains("/getCoverArt.view");
            byte[] body=binary?new byte[slow?32768:64]:"<subsonic-response status='ok' version='1.16.1'/>".getBytes("UTF-8");
            long length=slow?4*1024*1024:body.length+(truncated?32:0);
            output.write(("HTTP/1.1 "+(error?"503 Unavailable":"200 OK")+"\r\nContent-Type: "+(path.contains("/getCoverArt.view")?"image/png":binary?"audio/mpeg":"application/xml")+"\r\nContent-Length: "+length+"\r\nConnection: "+(close?"close":"keep-alive")+"\r\n\r\n").getBytes("ISO-8859-1"));
            if(slow){for(int n=0;n<128 && !closed;n++){output.write(body);output.flush();Thread.sleep(20);}}
            else {output.write(body);output.flush();}
            if(close || truncated)return;
        }
    }catch(Exception disconnected){/* Cancellation, TLS rejection and truncated transfers are expected. */}
    finally{try{socket.close();}catch(IOException ignored){}synchronized(clients){clients.remove(socket);}}}
    int active(){synchronized(clients){return clients.size();}}
    void close()throws Exception {closed=true;server.close();Socket[] sockets;synchronized(clients){sockets=clients.toArray(new Socket[clients.size()]);}for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}acceptor.join(2000);}
}
