package io.onloopio.player;
import java.io.*;
import java.util.*;
/** Bounded ID3v2 TXXX / FLAC Vorbis comments. Unsupported tags honestly yield missing gain. */
public final class LocalGainTags {
    public static ReplayGain read(File file)throws IOException{
        Map<String,Double> values=new HashMap<String,Double>();RandomAccessFile in=new RandomAccessFile(file,"r");try{
            byte[] header=new byte[10];if(in.read(header)!=10)return empty(values);
            if(header[0]=='f' && header[1]=='L' && header[2]=='a' && header[3]=='C'){
                in.seek(4);for(int block=0;block<64;block++){int type=in.readUnsignedByte(),length=(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();if(length>262144)break;if((type&127)==4){byte[] bytes=new byte[length];in.readFully(bytes);DataInputStream data=new DataInputStream(new ByteArrayInputStream(bytes));int vendor=le(data);if(vendor<0 || vendor>data.available())break;data.skipBytes(vendor);int count=le(data);if(count<0 || count>4096)break;for(int n=0;n<count;n++){int size=le(data);if(size<0 || size>16384 || size>data.available())break;byte[] comment=new byte[size];data.readFully(comment);String text=new String(comment,"UTF-8");int eq=text.indexOf('=');if(eq>0)put(values,text.substring(0,eq),text.substring(eq+1));}}else in.seek(in.getFilePointer()+length);if((type&128)!=0)break;}
            }else if(header[0]=='I' && header[1]=='D' && header[2]=='3' && (header[3]==3 || header[3]==4) && header[5]==0){
                int size=synchsafe(header,6);if(size>262144)return empty(values);long end=10L+size;
                while(in.getFilePointer()+10<=end){byte[] frame=new byte[10];in.readFully(frame);int length=header[3]==4?synchsafe(frame,4):((frame[4]&255)<<24)|((frame[5]&255)<<16)|((frame[6]&255)<<8)|(frame[7]&255);if(length<=0 || length>16384 || in.getFilePointer()+length>end)break;byte[] bytes=new byte[length];in.readFully(bytes);if(new String(frame,0,4,"ISO-8859-1").equals("TXXX") && frame[8]==0 && frame[9]==0){int encoding=bytes[0]&255;String charset=encoding==0?"ISO-8859-1":encoding==1?"UTF-16":encoding==2?"UTF-16BE":encoding==3?"UTF-8":null;if(charset!=null){String text=new String(bytes,1,bytes.length-1,charset);int zero=text.indexOf('\u0000');if(zero>0)put(values,text.substring(0,zero),text.substring(zero+1));}}}
            }
        }catch(EOFException truncated){}finally{in.close();}return empty(values);
    }
    private static int le(DataInputStream in)throws IOException{return in.readUnsignedByte()|(in.readUnsignedByte()<<8)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<24);}
    private static int synchsafe(byte[] b,int n){return ((b[n]&127)<<21)|((b[n+1]&127)<<14)|((b[n+2]&127)<<7)|(b[n+3]&127);}
    private static void put(Map<String,Double> values,String key,String value){key=key.toUpperCase(Locale.US);if(!key.startsWith("REPLAYGAIN_"))return;try{Double v=ReplayGain.finite(Double.valueOf(value.replace("dB","").trim()));if(v!=null)values.put(key,v);}catch(NumberFormatException invalid){}}
    private static ReplayGain empty(Map<String,Double> v){return new ReplayGain(v.get("REPLAYGAIN_TRACK_GAIN"),v.get("REPLAYGAIN_ALBUM_GAIN"),v.get("REPLAYGAIN_TRACK_PEAK"),v.get("REPLAYGAIN_ALBUM_PEAK"),null,null);}
}
