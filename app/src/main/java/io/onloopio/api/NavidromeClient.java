package io.onloopio.api;

import io.onloopio.model.Playlist;
import io.onloopio.model.PlaylistDetail;
import io.onloopio.model.Song;
import io.onloopio.model.Library;
import io.onloopio.model.ListenEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/** Blocking metadata client. Always call on a worker thread; never log authenticated URLs. */
public final class NavidromeClient {
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private final ServerConfig config;
    private final SecureRandom random = new SecureRandom();
    public NavidromeClient(ServerConfig config) { this.config = config; }
    public void ping() throws IOException { request("ping", null); }
    public List<Playlist> getPlaylists() throws IOException { return request("getPlaylists", null).playlists; }
    private static void serverSong(String id)throws IOException {if(id==null || id.length()==0 || id.startsWith("local:"))throw new IOException("Invalid server song ID.");}
    public void star(String id)throws IOException {serverSong(id);request("star",id);}
    public void unstar(String id)throws IOException {serverSong(id);request("unstar",id);}
    public List<Song> getStarred2()throws IOException {
        List<Song> songs=request("getStarred2",null).songs;java.util.Set<String> seen=new java.util.HashSet<String>();
        for(Song song:songs){serverSong(song.id);if(!seen.add(song.id))throw new IOException("Repeated starred song.");}return songs;
    }
    public void scrobble(List<ListenEvent> events)throws IOException {
        if(events==null || events.isEmpty() || events.size()>25)throw new IOException("Invalid scrobble batch.");
        StringBuilder extra=new StringBuilder("&submission=true");
        for(ListenEvent event:events){serverSong(event.songId);if(event.time<0 || !config.accountKey().equals(event.account))throw new IOException("Invalid scrobble account or time.");extra.append("&id=").append(encode(event.songId)).append("&time=").append(event.time);}
        request("scrobble",null,extra.toString());
    }
    public Library catalogPage(int artistOffset,int albumOffset,int songOffset,int count) throws IOException {
        if(count<1 || count>500 || artistOffset<0 || albumOffset<0 || songOffset<0) throw new IOException("Invalid catalog pagination.");
        return request("search3",null,"&query=&artistCount="+count+"&albumCount="+count+"&songCount="+count+"&artistOffset="+artistOffset+"&albumOffset="+albumOffset+"&songOffset="+songOffset).library;
    }
    /** Independent offsets: a shorter artist page must not truncate the songs. */
    public interface Check { void check()throws IOException; }
    public Library catalog() throws IOException {return catalog(null);}
    public Library catalog(Check check) throws IOException {
        Library result=new Library(); java.util.Set<String> seen=new java.util.HashSet<String>();
        int a=0,b=0,s=0;
        for(int page=0;page<2000;page++) {
            if(Thread.currentThread().isInterrupted())throw new IOException("Catalog check interrupted");if(check!=null)check.check();
            Library next=catalogPage(a,b,s,500);
            if(next.artists.isEmpty() && next.albums.isEmpty() && next.songs.isEmpty()) return result;
            for(Library.Entity e:next.artists) if(!seen.add("a"+e.id)) throw new IOException("Catalog pagination repeated an artist.");
            for(Library.Entity e:next.albums) if(!seen.add("b"+e.id)) throw new IOException("Catalog pagination repeated an album.");
            for(Song e:next.songs) if(!seen.add("s"+e.id)) throw new IOException("Catalog pagination repeated a song.");
            result.artists.addAll(next.artists); result.albums.addAll(next.albums); result.songs.addAll(next.songs);
            a+=next.artists.size(); b+=next.albums.size(); s+=next.songs.size();
        }
        throw new IOException("Catalog exceeds the device limit.");
    }
    public PlaylistDetail getPlaylist(String id) throws IOException {
        Response response = request("getPlaylist", id);
        if (response.playlists.size() != 1 || !id.equals(response.playlists.get(0).id))
            throw new IOException("Invalid playlist response.");
        return new PlaylistDetail(response.playlists.get(0), response.songs);
    }
    public static String authenticationToken(String password, String salt) throws IOException {
        try { return hex(MessageDigest.getInstance("MD5").digest((password + salt).getBytes("UTF-8"))); }
        catch (Exception e) { throw new IOException("Cannot create authentication token."); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) { int v = value & 255; text.append("0123456789abcdef".charAt(v >> 4)); text.append("0123456789abcdef".charAt(v & 15)); }
        return text.toString();
    }
    private static String encode(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }
    private HttpURLConnection connection(String method,String id,String accept) throws IOException {
        return connection(method,id,accept,"");
    }
    private HttpURLConnection connection(String method,String id,String accept,String extra) throws IOException {
        byte[] saltBytes = new byte[16]; random.nextBytes(saltBytes);
        String salt = hex(saltBytes);
        String query = "u=" + encode(config.username) + "&t=" + authenticationToken(config.password, salt) +
                "&s=" + salt + "&v=1.16.1&c=OnLoopio&f=xml" + (id == null ? "" : "&id=" + encode(id))+extra;
        HttpURLConnection connection = (HttpURLConnection) new URL(config.baseUrl + "/rest/" + method + ".view?" + query).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setRequestProperty("Accept",accept); connection.setRequestProperty("Accept-Encoding","identity");
        if(connection instanceof HttpsURLConnection) ((HttpsURLConnection)connection).setSSLSocketFactory(new Tls12SocketFactory(config.trustedCaPem));
        return connection;
    }
    public interface DownloadProgress { void bytes(long received,long total) throws IOException; }
    /** Bounded binary cover retrieval with the same verified TLS and authentication. */
    public byte[] cover(String id) throws IOException {
        HttpURLConnection c=connection("getCoverArt",id,"image/*","&size=256"); InputStream input=null;
        try {
            if(c.getResponseCode()!=200) throw new IOException("Cover unavailable.");
            String type=c.getContentType(); if(type==null || !type.toLowerCase(java.util.Locale.US).startsWith("image/")) throw new IOException("Invalid cover response.");
            if(c.getContentLength()>2*1024*1024) throw new IOException("Cover is too large.");
            input=c.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");if(bytes.size()+count>2*1024*1024)throw new IOException("Cover is too large.");bytes.write(buffer,0,count);}
            if(bytes.size()<16)throw new IOException("Empty cover."); return bytes.toByteArray();
        } finally {if(input!=null)try{input.close();}catch(IOException ignored){}c.disconnect();}
    }
    /** Open a progressive original stream; the caller owns and disconnects the connection. */
    public HttpURLConnection stream(String id,String range) throws IOException {
        return stream(id,range,"raw");
    }
    public HttpURLConnection stream(String id,String range,String format) throws IOException {
        if(!"raw".equals(format) && !"mp3".equals(format)) throw new IOException("Invalid audio format.");
        HttpURLConnection c=connection("stream",id,"audio/*","&format="+format+("mp3".equals(format)?"&maxBitRate=320":""));
        if(range!=null) {
            if(!range.matches("bytes=[0-9]+-[0-9]*")) throw new IOException("Invalid audio range.");
            c.setRequestProperty("Range",range);
        }
        try {
            int status=c.getResponseCode(); String type=c.getContentType();
            if(status!=200 && status!=206 && status!=416) throw new IOException("Stream request failed.");
            if(status!=416 && type!=null && (type.contains("xml") || type.contains("json") || type.startsWith("text/"))) throw new IOException("Server returned an audio error.");
            return c;
        } catch(IOException failure) { c.disconnect(); throw failure; }
    }
    /** Downloads the original audio; caller atomically publishes only a completely received file. */
    public void download(String id,File partial,long maximum,DownloadProgress progress) throws IOException {
        transfer(connection("download",id,"application/octet-stream"),partial,maximum,progress);
    }
    /** Save a compatible MP3 through Navidrome's stream transcoder for codecs absent on Y1. */
    public void downloadMp3(String id,File partial,long maximum,DownloadProgress progress) throws IOException {
        transfer(connection("stream",id,"audio/mpeg","&format=mp3&maxBitRate=320"),partial,maximum,progress);
    }
    private void transfer(HttpURLConnection connection,File partial,long maximum,DownloadProgress progress) throws IOException {
        InputStream input=null; FileOutputStream output=null; boolean complete=false;
        try {
            if(connection.getResponseCode()!=200) throw new IOException("Audio download failed. Check the connection.");
            String type=connection.getContentType();
            if(type!=null && (type.contains("xml") || type.contains("json") || type.startsWith("text/"))) throw new IOException("Server returned an audio error response.");
            long total=connection.getContentLength();
            if(total>maximum) throw new IOException("Audio file exceeds the download limit.");
            input=connection.getInputStream(); output=new FileOutputStream(partial);
            byte[] buffer=new byte[32768]; long received=0; int count;
            while((count=input.read(buffer))!=-1) {
                if(Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled.");
                if(received+count>maximum) throw new IOException("Audio file exceeds the download limit.");
                output.write(buffer,0,count); received+=count;
                if(progress!=null) progress.bytes(received,total);
            }
            if(received<16 || (total>=0 && received!=total)) throw new IOException("Incomplete audio download.");
            output.getFD().sync(); complete=true;
        } finally {
            if(output!=null) try { output.close(); } catch(IOException ignored) { }
            if(input!=null) try { input.close(); } catch(IOException ignored) { }
            connection.disconnect(); if(!complete && partial.isFile()) partial.delete();
        }
    }
    private Response request(String method, String id) throws IOException {
        return request(method,id,"");
    }
    private Response request(String method,String id,String extra) throws IOException {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection=connection(method,id,"application/xml",extra);
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status + ". Check the server URL and network.");
            input = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
                if (bytes.size() + count > MAX_RESPONSE) throw new IOException("Server metadata response is too large.");
                bytes.write(buffer, 0, count);
            }
            return parse(bytes.toByteArray(), method);
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (connection != null) connection.disconnect();
        }
    }
    private static final class Response {
        final List<Playlist> playlists = new ArrayList<Playlist>();
        final List<Song> songs = new ArrayList<Song>();
        final Library library=new Library();
    }
    private static Response parse(byte[] bytes, String method) throws IOException {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
            Response result = new Response();
            boolean root = false, rootClosed = false, ok = false, collection = false; int error = -1;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.nextToken()) {
                if (event == XmlPullParser.DOCDECL) throw new IOException("Unsupported XML document declaration.");
                if (event == XmlPullParser.END_TAG && parser.getDepth() == 1) rootClosed = true;
                if (event != XmlPullParser.START_TAG) continue;
                String tag = parser.getName();
                if (parser.getDepth() == 1) {
                    if (root) throw new IOException("Multiple XML roots.");
                    root = "subsonic-response".equals(tag);
                    ok = "ok".equals(attribute(parser, "status", ""));
                    if (!root) throw new IOException("Invalid server response.");
                } else if ("error".equals(tag)) {
                    error = integer(parser, "code");
                } else if ("playlists".equals(tag) && parser.getDepth() == 2) {
                    collection = true;
                } else if("searchResult3".equals(tag) && parser.getDepth()==2 && "search3".equals(method)) {
                    collection=true;
                } else if("starred2".equals(tag) && parser.getDepth()==2 && "getStarred2".equals(method)) {
                    collection=true;
                } else if("search3".equals(method) && collection && parser.getDepth()==3 && ("artist".equals(tag) || "album".equals(tag))) {
                    String id=attribute(parser,"id",""); if(id.length()==0) throw new IOException("Catalog entity missing ID.");
                    Library.Entity entity=new Library.Entity(id,attribute(parser,"name","Untitled"),attribute(parser,"artistId",""),attribute(parser,"artist",""));
                    ("artist".equals(tag)?result.library.artists:result.library.albums).add(entity);
                } else if ("playlist".equals(tag) &&
                        (("getPlaylist".equals(method) && parser.getDepth() == 2) ||
                         ("getPlaylists".equals(method) && collection && parser.getDepth() == 3))) {
                    String id = attribute(parser, "id", "");
                    if (id.length() == 0) throw new IOException("Playlist is missing its ID.");
                    result.playlists.add(new Playlist(id, attribute(parser, "name", "Untitled"),
                            attribute(parser, "changed", ""), integer(parser, "songCount"), number(parser, "duration")));
                } else if ((("entry".equals(tag) && "getPlaylist".equals(method)) || ("song".equals(tag) && ("search3".equals(method) || "getStarred2".equals(method)) && collection)) && parser.getDepth() == 3) {
                    String id = attribute(parser, "id", "");
                    if (id.length() == 0) throw new IOException("Song is missing its ID.");
                    Song song=new Song(id, attribute(parser, "title", "Untitled"), attribute(parser, "artist", ""),
                            attribute(parser, "album", ""), attribute(parser, "suffix", ""), integer(parser, "duration"), attribute(parser,"albumId",""),integer(parser,"track"),attribute(parser,"artistId",""),attribute(parser,"genre",""),integer(parser,"discNumber"),attribute(parser,"coverArt",""));
                    result.songs.add(song); if("search3".equals(method)) result.library.songs.add(song);
                }
            }
            if (!root || !rootClosed) throw new IOException("Empty or incomplete server response.");
            if (!ok) throw new ApiException(error < 0 ? 0 : error);
            if ("getPlaylists".equals(method) && !collection) throw new IOException("Invalid playlist list response.");
            if("search3".equals(method) && !collection) throw new IOException("Invalid catalog response.");
            if("getStarred2".equals(method) && !collection) throw new IOException("Invalid starred response.");
            return result;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Cannot read server XML response."); }
    }
    private static String attribute(XmlPullParser parser, String name, String fallback) {
        String value = parser.getAttributeValue(null, name); return value == null ? fallback : value;
    }
    private static long number(XmlPullParser parser, String name) throws IOException {
        try { long value = Long.parseLong(attribute(parser, name, "0")); if (value < 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException e) { throw new IOException("Invalid numeric metadata."); }
    }
    private static int integer(XmlPullParser parser, String name) throws IOException {
        long value = number(parser, name);
        if (value > Integer.MAX_VALUE) throw new IOException("Invalid numeric metadata.");
        return (int) value;
    }
}
