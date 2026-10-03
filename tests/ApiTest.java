import io.onloopio.api.ApiException;
import io.onloopio.api.NavidromeClient;
import io.onloopio.api.ServerConfig;
import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Library;
import io.onloopio.model.ListenEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run the production Java client against a real HTTP fixture, without Android stubs. */
public final class ApiTest {
    static final String HEAD = "<subsonic-response xmlns='http://subsonic.org/restapi' status='ok' version='1.16.1'>";
    static final String END = "</subsonic-response>";
    static volatile String response;
    static volatile int status = 200;
    static volatile String contentType="application/xml";
    static volatile int declaredExtra=0;
    static volatile boolean validAuth = true;
    static volatile boolean closesConnections = true;
    static final Set<String> salts = new HashSet<String>();
    static int checks;
    static volatile Map<String,String> lastParams;
    static volatile String lastPath,lastQuery;
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
    interface Action { void run() throws Exception; }
    static void rejects(Action action, String label) throws Exception {
        try { action.run(); } catch (IOException e) { checks++; return; }
        throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        check("26719a1196d2a940705a59634eb18eab".equals(NavidromeClient.authenticationToken("sesame","c19b2d")),"Official token example");
        try { new ServerConfig("https://user:pass@localhost","a","b"); throw new AssertionError("URL credentials accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        HttpFixture fixture = new HttpFixture(new InetSocketAddress("127.0.0.1",0));
        fixture.createContext("/prefix/rest/",new HttpFixture.Handler() {
            public void handle(HttpFixture.Exchange exchange) throws IOException {
                closesConnections &= "close".equalsIgnoreCase(exchange.getRequestHeaders().values.get("connection"));
                Map<String,String> params = new HashMap<String,String>();
                for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                    String[] kv=part.split("=",2); params.put(URLDecoder.decode(kv[0],"UTF-8"),URLDecoder.decode(kv[1],"UTF-8"));
                }
                String salt = params.get("s");
                lastParams=params;lastPath=exchange.getRequestURI().getPath();lastQuery=exchange.getRequestURI().getRawQuery();
                validAuth &= "name + Ж".equals(params.get("u")) && salt != null && salt.length() == 32 && salts.add(salt) &&
                        NavidromeClient.authenticationToken("pass&Ж",salt).equals(params.get("t")) && !params.containsKey("p");
                byte[] bytes = response.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type",contentType);
                exchange.sendResponseHeaders(status,bytes.length+declaredExtra); exchange.getResponseBody().write(bytes); exchange.close();
            }
        });
        fixture.start();
        final NavidromeClient client = new NavidromeClient(new ServerConfig("http://127.0.0.1:"+fixture.getAddress().getPort()+"/prefix/","name + Ж","pass&Ж"));
        try {
            response = HEAD+END; client.ping(); checks++;
            client.star("song & Ж");check(lastPath.endsWith("/star.view") && "song & Ж".equals(lastParams.get("id")),"Star ID and endpoint");
            client.unstar("song & Ж");check(lastPath.endsWith("/unstar.view"),"Unstar endpoint");
            response=HEAD+"<starred2><artist id='ar'/><album id='al'/><song id='liked' title='Favorite' duration='60'/></starred2>"+END;
            check(client.getStarred2().size()==1 && "liked".equals(client.getStarred2().get(0).id),"Only starred songs, ignoring albums and artists");
            response=HEAD+"<starred2/>"+END;check(client.getStarred2().isEmpty(),"Empty starred snapshot");
            response=HEAD+END;rejects(new Action(){public void run()throws Exception{client.getStarred2();}},"Missing starred root");
            response=HEAD+"<starred2><song id='same'/><song id='same'/></starred2>"+END;rejects(new Action(){public void run()throws Exception{client.getStarred2();}},"Repeated starred IDs");
            response=HEAD+"<starred2><song title='missing'/></starred2>"+END;rejects(new Action(){public void run()throws Exception{client.getStarred2();}},"Starred song missing ID");
            response=HEAD+"<starred2><song id='s'/>";rejects(new Action(){public void run()throws Exception{client.getStarred2();}},"Truncated starred XML");
            response=HEAD+END;
            String account="http://127.0.0.1:"+fixture.getAddress().getPort()+"/prefix\nname + Ж";
            client.scrobble(java.util.Arrays.asList(new ListenEvent("one",account,"repeat & Ж",1000),new ListenEvent("two",account,"repeat & Ж",2000)));
            check(lastPath.endsWith("/scrobble.view") && "true".equals(lastParams.get("submission")),"Scrobble submission");
            check(lastQuery.contains("&time=1000") && lastQuery.contains("&time=2000") && lastQuery.split("&id=",-1).length==3,"Repeated IDs and original times retained");
            rejects(new Action(){public void run()throws Exception{client.scrobble(java.util.Collections.singletonList(new ListenEvent("bad","other","s",1000)));}},"Wrong scrobble account");
            rejects(new Action(){public void run()throws Exception{client.star("local:file");}},"Local track sent to server");
            response = HEAD+"<playlists/>"+END; check(client.getPlaylists().isEmpty(),"Empty list");
            response = HEAD+"<playlists><playlist id='p&amp;1' name='Daily &amp; Mix' songCount='2' duration='123' changed='today'/></playlists>"+END;
            List<Playlist> playlists = client.getPlaylists();
            check(playlists.size()==1 && playlists.get(0).name.equals("Daily & Mix") && playlists.get(0).songCount==2,"Escaped playlist");
            response = HEAD+"<playlist id='p&amp;1' name='Mix' songCount='3'><entry id='b' title='Second' duration='4'/><entry id='a' title='First'/><entry id='b' title='Again'/></playlist>"+END;
            PlaylistDetail detail = client.getPlaylist("p&1");
            check(detail.songs.size()==3 && detail.songs.get(0).id.equals("b") && detail.songs.get(2).id.equals("b"),"Order and repeated entries");
            response=HEAD+"<searchResult3><artist id='ar1' name='One'/><album id='al1' name='Record' artistId='ar1' artist='One'/><song id='s1' title='Tune' artist='One' artistId='ar1' album='Record' albumId='al1' genre='Jazz' discNumber='2' track='3' suffix='wv' duration='90' coverArt='mf-cover'/></searchResult3>"+END;
            Library page=client.catalogPage(0,0,0,100);
            check(page.artists.size()==1 && page.albums.size()==1 && page.songs.size()==1,"Catalog entities");
            check(page.songs.get(0).artistId.equals("ar1") && page.songs.get(0).disc==2 && page.songs.get(0).genre.equals("Jazz"),"Catalog metadata");
            check(page.songs.get(0).coverArt.equals("mf-cover"),"Cover identifier");
            contentType="image/png";response="a-binary-cover-fixture";check(client.cover("mf-cover").length>16,"Binary cover retrieval");
            contentType="application/xml";response=HEAD+END;rejects(new Action(){public void run()throws Exception{client.cover("bad");}},"Cover XML rejection");
            contentType="image/png";response=new String(new char[2*1024*1024+1]).replace('\0','x');rejects(new Action(){public void run()throws Exception{client.cover("huge");}},"Cover size bound");contentType="application/xml";
            response=HEAD+"<searchResult3/>"+END; check(client.catalog().songs.isEmpty(),"Empty full catalog");
            response=HEAD+END; rejects(new Action(){public void run() throws Exception{client.catalogPage(0,0,0,50);}},"Missing catalog root");
            response = "<subsonic-response status='failed'><error code='40' message='secret server text'/></subsonic-response>";
            try { client.ping(); throw new AssertionError("Auth failure accepted"); }
            catch (ApiException e) { check(e.code==40 && !e.getMessage().contains("secret"),"Safe API error"); }
            rejects(new Action(){public void run()throws Exception{client.star("s");}},"Failed star acknowledged");
            response = HEAD+END; rejects(new Action(){public void run() throws Exception {client.getPlaylists();}},"Missing collection");
            response = "<html>proxy</html>"; rejects(new Action(){public void run() throws Exception {client.ping();}},"HTML response");
            response = HEAD+"<playlists><playlist name='missing ID'/></playlists>"+END; rejects(new Action(){public void run() throws Exception {client.getPlaylists();}},"Missing ID");
            response = HEAD+"<playlists><playlist id='p' songCount='-1'/></playlists>"+END; rejects(new Action(){public void run() throws Exception {client.getPlaylists();}},"Negative count");
            response = HEAD+"<playlists><playlist id='p' songCount='2147483648'/></playlists>"+END; rejects(new Action(){public void run() throws Exception {client.getPlaylists();}},"Count overflow");
            response = HEAD+"<playlist id='wrong'/>"+END; rejects(new Action(){public void run() throws Exception {client.getPlaylist("p");}},"Wrong playlist");
            response = HEAD+"<playlists>"; rejects(new Action(){public void run() throws Exception {client.getPlaylists();}},"Truncated XML");
            response = HEAD+END; status=302; rejects(new Action(){public void run() throws Exception {client.ping();}},"Redirect rejected"); status=200;
            final java.io.File partial=java.io.File.createTempFile("onloopio-audio-",".part"); partial.delete();
            try {
                contentType="application/xml"; response=HEAD+END;
                rejects(new Action(){ public void run() throws Exception { client.download("audio",partial,1024,null); }},"XML audio error accepted"); check(!partial.exists(),"XML error left partial audio");
                contentType="audio/mpeg"; response="ID3abcdefghijklmnopqrstuv";
                client.download("audio",partial,1024,null); check(partial.length()==response.length(),"Complete download size"); partial.delete();
                rejects(new Action(){ public void run() throws Exception { client.download("audio",partial,16,null); }},"Oversized audio accepted"); check(!partial.exists(),"Oversized audio left partial");
                rejects(new Action(){ public void run() throws Exception { client.download("audio",partial,1024,new NavidromeClient.DownloadProgress(){ public void bytes(long received,long total) throws IOException { throw new IOException("Cancel fixture"); }}); }},"Cancelled audio accepted"); check(!partial.exists(),"Cancellation left partial");
                declaredExtra=64; rejects(new Action(){ public void run() throws Exception { client.download("audio",partial,1024,null); }},"Truncated audio accepted"); check(!partial.exists(),"Truncated audio left partial"); declaredExtra=0;
            } finally { declaredExtra=0; partial.delete(); }
            check(validAuth,"UTF-8 auth, fresh salt per request, no cleartext password");
            check(closesConnections,"Metadata, covers and audio requests release rather than pool sockets");
        } finally { fixture.stop(0); }
        System.out.println("API fixture checks passed: "+checks);
        String url = System.getenv("ONLOOPIO_URL");
        if (url != null) {
            String caFile = System.getenv("ONLOOPIO_CA_FILE");
            String caPem = caFile == null ? "" : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(caFile)),"UTF-8");
            NavidromeClient live = new NavidromeClient(new ServerConfig(url,System.getenv("ONLOOPIO_USERNAME"),System.getenv("ONLOOPIO_PASSWORD"),caPem));
            live.ping(); List<Playlist> playlists=live.getPlaylists();
            System.out.println("Live Navidrome ping: OK; playlists: "+playlists.size());
            if (!playlists.isEmpty()) System.out.println("Live getPlaylist: OK; entries: "+live.getPlaylist(playlists.get(0).id).songs.size());
            Library catalog=live.catalog(); java.util.Map<String,Integer> formats=new java.util.TreeMap<String,Integer>();
            for(io.onloopio.model.Song song:catalog.songs) {Integer n=formats.get(song.suffix);formats.put(song.suffix,n==null?1:n+1);}
            System.out.println("Live full catalog: "+catalog.artists.size()+" artists; "+catalog.albums.size()+" albums; "+catalog.songs.size()+" songs; formats "+formats);
            for(io.onloopio.model.Song song:catalog.songs) if("opus".equals(song.suffix)) {
                java.net.HttpURLConnection stream=live.stream(song.id,null,"mp3");
                try {byte[] header=new byte[32]; java.io.InputStream audio=stream.getInputStream();int count=audio.read(header);check(count>0 && (header[0]=='I' || (header[0]&255)==255),"Live Opus-to-MP3 streaming");audio.close();}
                finally {stream.disconnect();} break;
            }
        }
    }
}
