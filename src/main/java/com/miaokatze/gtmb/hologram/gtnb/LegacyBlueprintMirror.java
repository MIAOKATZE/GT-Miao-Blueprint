// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram.gtnb;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;

/** Conservative expanded PERSISTENCE identity for a migrated loose file and a retained collection mirror.
 * It is deliberately stricter than native construction content identity: authorship, order, metadata, raw
 * payload references (now expanded), unknown fields and all meaningful envelope values participate. */
public final class LegacyBlueprintMirror {
    private static final Pattern UUID = Pattern.compile("(?:local_)?[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}");
    private LegacyBlueprintMirror() {}

    public static String logicalId(String id) {
        return id!=null&&UUID.matcher(id).matches()
            ? (id.startsWith("local_")?id:"local_"+id).toLowerCase(Locale.ROOT) : "";
    }

    public static String digest(JsonObject expandedRoot,JsonObject member,String expectedId) throws IOException {
        JsonElement id=member.get("id");
        if(id==null||!id.isJsonPrimitive()||!id.getAsJsonPrimitive().isString())return "";
        String logical=logicalId(id.getAsString());
        if(logical.isEmpty()||!logical.equals(logicalId(expectedId)))return "";
        try {
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            sha.update("GTMB-LEGACY-MIRROR\0v1\0".getBytes(StandardCharsets.US_ASCII));
            OutputStream sink=new OutputStream(){@Override public void write(int value) {} @Override public void write(byte[] value,int a,int n) {}};
            try(JsonWriter out=new JsonWriter(new OutputStreamWriter(new DigestOutputStream(sink,sha),StandardCharsets.UTF_8.newEncoder()))){
                out.setSerializeNulls(true);
                out.beginArray();
                writeObject(out,member,logical,Collections.emptySet(),0);
                // Only wrapper fields known to be storage mechanics are excluded. If unknown business
                // fields exist in one envelope but not the other, keep the other card visible.
                Set<String> wrapper=new java.util.HashSet<>(java.util.Arrays.asList("blueprint","presets","schema","previewBinding"));
                if(expandedRoot==member){out.beginObject();out.endObject();}
                else writeObject(out,expandedRoot,null,wrapper,0);
                out.endArray();
            }
            StringBuilder result=new StringBuilder(64);
            for(byte b:sha.digest())result.append(Character.forDigit((b&255)>>>4,16)).append(Character.forDigit(b&15,16));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }

    private static void writeObject(JsonWriter out,JsonObject object,String logical,Set<String> skip,int depth)throws IOException {
        List<String> keys=new ArrayList<>();
        for(var e:object.entrySet())if(!skip.contains(e.getKey()))keys.add(e.getKey());
        Collections.sort(keys);out.beginObject();
        for(String k:keys){
            out.name(k);
            if(logical!=null&&k.equals("id"))out.value(logical);
            else write(out,object.get(k),depth+1);
        }
        out.endObject();
    }

    private static void write(JsonWriter out,JsonElement element,int depth)throws IOException{
        if(depth>NativeBlueprintLimits.DEPTH)throw new IOException("legacy mirror depth");
        if(element==null||element.isJsonNull()){out.nullValue();return;}
        if(element.isJsonObject()){writeObject(out,element.getAsJsonObject(),null,Collections.emptySet(),depth);return;}
        if(element.isJsonArray()){
            out.beginArray();for(JsonElement item:element.getAsJsonArray())write(out,item,depth+1);out.endArray();
            return;
        }
        JsonPrimitive value=element.getAsJsonPrimitive();
        if(value.isBoolean())out.value(value.getAsBoolean());else if(value.isNumber())out.value(value.getAsNumber());else out.value(value.getAsString());
    }

    public static boolean hidden(JsonObject expandedRoot,JsonObject member,String id,Set<String> proven) {
        if(proven==null||proven.isEmpty())return false;
        try{return proven.contains(digest(expandedRoot,member,id));}
        catch(IOException|RuntimeException invalid){return false;} // Never hide unproved/unreadable contents.
    }
}
