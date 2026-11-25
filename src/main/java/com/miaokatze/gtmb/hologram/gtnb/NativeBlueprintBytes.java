// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class NativeBlueprintBytes {
    private NativeBlueprintBytes() {}
    static IOException bad(String field) { return new IOException("Native blueprint: " + field); }
    static void need(boolean valid, String field) throws IOException { if (!valid) throw bad(field); }
    static byte[] read(FileChannel file, long offset, int length) throws IOException {
        need(offset >= 0 && length >= 0 && length <= NativeBlueprintLimits.FILE_BYTES && offset <= file.size()-length, "read bounds");
        ByteBuffer b = ByteBuffer.allocate(length);
        while (b.hasRemaining()) { int n = file.read(b, offset+b.position()); if (n < 0) throw bad("truncated"); }
        return b.array();
    }
    static int compare(byte[] a, byte[] b) { for (int i=0;i<Math.min(a.length,b.length);i++) { int c=(a[i]&255)-(b[i]&255); if(c!=0)return c; } return Integer.compare(a.length,b.length); }
    static String hash(byte[] bytes) { try { byte[] h=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder s=new StringBuilder(); for(byte x:h)s.append(String.format("%02x",x&255));return s.toString(); } catch(NoSuchAlgorithmException e){throw new AssertionError(e);} }
    static final class In {
        final byte[] data; int at;
        In(byte[] data){this.data=data;}
        int left(){return data.length-at;}
        byte[] take(int n)throws IOException {need(n>=0&&n<=left(),"truncated record");byte[] b=Arrays.copyOfRange(data,at,at+n);at+=n;return b;}
        int u8()throws IOException{need(left()>0,"truncated byte");return data[at++]&255;}
        int u16()throws IOException{return u8()|(u8()<<8);}
        int i32()throws IOException{return u8()|(u8()<<8)|(u8()<<16)|(u8()<<24);}
        long u32()throws IOException{return i32()&0xffffffffL;}
        long u64()throws IOException{long v=u32()|(u32()<<32);need(v>=0,"unsigned long overflow");return v;}
        long var(int bits)throws IOException {long v=0;int shift=0,n=0,x;do{x=u8(); n++;need(n<=(bits+6)/7,"varint too long");int payload=x&127;need(shift<63&&(shift+7<=bits||payload<(1L<<(bits-shift))),"varint overflow");v|=(long)payload<<shift;shift+=7;}while((x&128)!=0);need(n==1||(x&127)!=0,"nonminimal varint");return v;}
        int count(int cap)throws IOException{long n=var(32);need(n<=cap,"count budget");return(int)n;}
        byte[] blob(int cap)throws IOException{return take(count(cap));}
        void end()throws IOException{need(left()==0,"trailing record bytes");}
    }
    static final class Out extends ByteArrayOutputStream {
        void u8(int x){write(x);}
        void u16(int x){u8(x);u8(x>>>8);}
        void i32(int x){u8(x);u8(x>>>8);u8(x>>>16);u8(x>>>24);}
        void u32(long x){i32((int)x);}
        void u64(long x){u32(x);u32(x>>>32);}
        void var(long x){if(x<0)throw new IllegalArgumentException("negative varint");while(x>=128){u8((int)x|128);x>>>=7;}u8((int)x);}
        void bytes(byte[] b){write(b,0,b.length);}
        void blob(byte[] b){var(b.length);bytes(b);}
    }
}
