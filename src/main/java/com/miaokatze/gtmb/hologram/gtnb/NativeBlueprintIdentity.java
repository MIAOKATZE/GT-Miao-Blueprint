// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonObject;
import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.*;

/** Storage content identity. It grants no world permission and is not a construction fingerprint. */
public final class NativeBlueprintIdentity {
    private NativeBlueprintIdentity(){}
    public static String content(NativeBlueprintDefinition d)throws IOException {
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update("GTMB-NATIVE-CONTENT\0v1\0".getBytes(StandardCharsets.US_ASCII));
            JsonObject meta=d.metadata().getAsJsonObject("fields");for(String k:new String[]{"id","name","authorName","authorUuid","createdAt"})meta.remove(k);blob(digest,NativeBlueprintJson.encode(meta));
            blob(digest,java.util.Arrays.copyOf(d.rawSection(3),36));blob(digest,d.rawSection(2));
            // Geometry digest is canonical events, independent of page splits. Only one layer's runs at a time.
            long geometryLength=varLength(d.layerYs().size());for(int y:d.layerYs()){geometryLength+=4;long count=0;try(NativeBlueprintDefinition.RunCursor c=d.layerCursor(y)){long previous=-1,length=0;int element=0;while(c.next()){if(c.start()==previous+length&&c.element()==element)length+=c.length();else{if(length>0){geometryLength+=varLength(previous)+varLength(length)+varLength(element);count++;}previous=c.start();length=c.length();element=c.element();}}if(length>0){geometryLength+=varLength(previous)+varLength(length)+varLength(element);count++;}}geometryLength+=varLength(count);}
            prefix(digest,geometryLength);updateVar(digest,d.layerYs().size());for(int y:d.layerYs()){Out out=new Out();out.i32(y);digest.update(out.toByteArray());long count=merged(d,y,null);updateVar(digest,count);merged(d,y,digest);}

            long payloadBytes=varLength(d.payloadCount());for(int i=1;i<=d.payloadCount();i++)payloadBytes+=1+varLength(d.payloadLength(i))+d.payloadLength(i);prefix(digest,payloadBytes);updateVar(digest,d.payloadCount());for(int i=1;i<=d.payloadCount();i++){digest.update((byte)1);byte[] b=d.payload(i);updateVar(digest,b.length);digest.update(b);}
            byte[] emptyExtras={0,0,0};blob(digest,d.sectionBytes(7)>0?d.rawSection(7):emptyExtras);blob(digest,d.sectionBytes(8)>0?d.rawSection(8):NativeBlueprintJson.encode(NativeBlueprintDefinition.emptyWrap()));
            if(d.sectionBytes(9)>0)blob(digest,d.rawSection(9));else{JsonObject fields=new JsonObject();fields.add("document",new JsonObject());fields.add("envelope",new JsonObject());fields.add("legacyEnvelope",new JsonObject());JsonObject w=new JsonObject();w.add("fields",fields);blob(digest,NativeBlueprintJson.encode(w));}
            blob(digest,d.sectionBytes(10)>0?d.rawSection(10):new byte[]{0});Out extensions=new Out();extensions.var(d.extensions().size());for(NativeBlueprintDefinition.Section section:d.extensions()){extensions.u32(section.type);extensions.u32(section.flags);extensions.u32(section.version);extensions.u32(section.codec);extensions.blob(d.rawSection(section.type));}blob(digest,extensions.toByteArray());StringBuilder s=new StringBuilder("gtmb-native-content-1:");for(byte b:digest.digest())s.append(String.format("%02x",b&255));return s.toString();
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static long merged(NativeBlueprintDefinition d,int y,MessageDigest digest)throws IOException {long count=0,start=-1,length=0;int element=0;try(NativeBlueprintDefinition.RunCursor c=d.layerCursor(y)){while(c.next()){if(length>0&&c.start()==start+length&&c.element()==element){length+=c.length();continue;}if(length>0){if(digest!=null){updateVar(digest,start);updateVar(digest,length);updateVar(digest,element);}count++;}start=c.start();length=c.length();element=c.element();}if(length>0){if(digest!=null){updateVar(digest,start);updateVar(digest,length);updateVar(digest,element);}count++;}}return count;}
    private static int varLength(long n){int l=1;while(n>=128){n>>>=7;l++;}return l;}
    private static void updateVar(MessageDigest d,long n){Out o=new Out();o.var(n);d.update(o.toByteArray());}
    private static void prefix(MessageDigest d,long n)throws IOException{need(n>=0&&n<=0xffffffffL,"identity section length");updateVar(d,n);}
    private static void blob(MessageDigest d,byte[] b)throws IOException{prefix(d,b.length);d.update(b);}
}
