// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Raw 1.7.10 NBT validation without loading Minecraft or modifying a tag. */
final class NativeBlueprintNbt {
    private NativeBlueprintNbt() {}
    static byte[] gunzip(byte[] data,int expected)throws IOException {
        NativeBlueprintBytes.need(data.length<=NativeBlueprintLimits.NBT_BYTES&&expected<=NativeBlueprintLimits.NBT_BYTES,"NBT budget");
        int at=10;NativeBlueprintBytes.need(data.length>=18&&(data[0]&255)==31&&(data[1]&255)==139&&data[2]==8,"gzip header");int flags=data[3]&255;NativeBlueprintBytes.need((flags&224)==0,"gzip flags");
        if((flags&4)!=0){NativeBlueprintBytes.need(at+2<=data.length,"gzip extra");int n=(data[at]&255)|((data[at+1]&255)<<8);at+=2;NativeBlueprintBytes.need(n<=data.length-at,"gzip extra");at+=n;}
        for(int bit:new int[]{8,16})if((flags&bit)!=0){while(at<data.length&&data[at]!=0)at++;NativeBlueprintBytes.need(at<data.length,"gzip string");at++;}
        if((flags&2)!=0){NativeBlueprintBytes.need(at+2<=data.length,"gzip header CRC");CRC32 c=new CRC32();c.update(data,0,at);NativeBlueprintBytes.need(((int)c.getValue()&65535)==((data[at]&255)|((data[at+1]&255)<<8)),"gzip header CRC");at+=2;}
        Inflater inflater=new Inflater(true);ByteArrayOutputStream out=new ByteArrayOutputStream();
        try{inflater.setInput(data,at,data.length-at);byte[] b=new byte[8192];while(!inflater.finished()){int n=inflater.inflate(b);NativeBlueprintBytes.need(out.size()<=NativeBlueprintLimits.NBT_BYTES-n,"NBT expanded budget");out.write(b,0,n);if(n==0)NativeBlueprintBytes.need(!inflater.needsInput()&&!inflater.needsDictionary(),"gzip truncated");}
            int trailer=data.length-inflater.getRemaining();NativeBlueprintBytes.need(trailer+8==data.length,"gzip multiple members or trailer");byte[] raw=out.toByteArray();NativeBlueprintBytes.In t=new NativeBlueprintBytes.In(Arrays.copyOfRange(data,trailer,data.length));CRC32 crc=new CRC32();crc.update(raw);NativeBlueprintBytes.need(t.u32()==crc.getValue()&&t.u32()==raw.length,"gzip CRC/size");NativeBlueprintBytes.need(expected<0||raw.length==expected,"NBT expanded length");return raw;
        }catch(DataFormatException e){throw new IOException("Native blueprint: gzip",e);}finally{inflater.end();}
    }
    static byte[] gzip(byte[] raw)throws IOException {
        NativeBlueprintBytes.Out out=new NativeBlueprintBytes.Out();out.bytes(new byte[]{31,(byte)139,8,0,0,0,0,0,0,(byte)255});Deflater d=new Deflater(6,true);try{d.setInput(raw);d.finish();byte[] b=new byte[8192];while(!d.finished()){int n=d.deflate(b);out.write(b,0,n);}}finally{d.end();}CRC32 crc=new CRC32();crc.update(raw);out.u32(crc.getValue());out.u32(raw.length);return out.toByteArray();
    }
    static int validate(byte[] raw)throws IOException {NativeBlueprintBytes.need(raw.length<=NativeBlueprintLimits.NBT_BYTES,"NBT bytes");Parser p=new Parser(raw);NativeBlueprintBytes.need(p.in.readUnsignedByte()==10,"NBT root compound");p.name();p.value(10,1);NativeBlueprintBytes.need(p.in.available()==0,"NBT trailer");return p.nodes;}
    private static final class Parser {
        final DataInputStream in;int nodes;Parser(byte[] b){in=new DataInputStream(new ByteArrayInputStream(b));}
        void skip(long n)throws IOException {NativeBlueprintBytes.need(n>=0&&n<=in.available(),"NBT length");in.skipBytes((int)n);}
        void name()throws IOException {
            int left=in.readUnsignedShort();
            NativeBlueprintBytes.need(left<=in.available(),"NBT string length");
            while(left>0){
                int a=in.readUnsignedByte();left--;
                if(a>=1&&a<=127)continue;
                if(a>=192&&a<=223){
                    NativeBlueprintBytes.need(left>=1,"truncated MUTF8");
                    int b=in.readUnsignedByte();left--;
                    NativeBlueprintBytes.need((b&192)==128&&(a>=194||a==192&&b==128),"noncanonical MUTF8 pair");
                }else{
                    NativeBlueprintBytes.need(a>=224&&a<=239&&left>=2,"MUTF8 lead");
                    int b=in.readUnsignedByte(),c=in.readUnsignedByte();left-=2;
                    NativeBlueprintBytes.need((b&192)==128&&(c&192)==128&&(a!=224||b>=160),"noncanonical MUTF8 triple");
                }
            }
        }
        void value(int t,int depth)throws IOException {NativeBlueprintBytes.need(++nodes<=NativeBlueprintLimits.NBT_NODES&&depth<=NativeBlueprintLimits.DEPTH,"NBT work budget");switch(t){
            case 1:skip(1);break;case 2:skip(2);break;case 3:case 5:skip(4);break;case 4:case 6:skip(8);break;
            case 7:skip(in.readInt());break;case 8:name();break;
            case 9:{int k=in.readUnsignedByte(),n=in.readInt();NativeBlueprintBytes.need(n>=0&&n<=NativeBlueprintLimits.NBT_NODES-nodes&&(k>=1&&k<=11||k==0&&n==0),"NBT list");for(int i=0;i<n;i++)value(k,depth+1);break;}
            case 10:while(true){int k=in.readUnsignedByte();if(k==0)break;NativeBlueprintBytes.need(k>=1&&k<=11,"NBT tag type");name();value(k,depth+1);}break;
            case 11:skip((long)in.readInt()*4);break;default:throw NativeBlueprintBytes.bad("NBT tag type");}}
    }
}
