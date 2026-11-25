// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram.gtnb;

import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable sparse authored differences. Flags never alter positions or NBT; metadata-only edits do not touch geometry. */
public final class NativeBlueprintEdits {
    private final NativeBlueprintDefinition source;
    private final SortedMap<Integer,JsonObject> changes;
    private final JsonObject meta,document;
    private final java.lang.ref.WeakReference<NativeBlueprintEdits> prior;
    private final int[] touched;
    public NativeBlueprintEdits(NativeBlueprintDefinition source)throws IOException{
        this.prior=null;this.touched=new int[0];this.source=source;this.changes=Collections.emptySortedMap();
        this.meta=source.metadata();JsonObject d=source.sectionWrapper(9);
        if(d==null){d=NativeBlueprintDefinition.emptyWrap();JsonObject fields=d.getAsJsonObject("fields");for(String k:new String[]{"document","envelope","legacyEnvelope"})fields.add(k,new JsonObject());}
        this.document=d;
    }
    private NativeBlueprintEdits(NativeBlueprintEdits old,SortedMap<Integer,JsonObject> c,JsonObject m,JsonObject d,int... touched){
        prior=new java.lang.ref.WeakReference<>(old);this.touched=touched.clone();
        source=old.source;changes=Collections.unmodifiableSortedMap(c);meta=m;document=d;
    }
    public NativeBlueprintDefinition source(){return source;}
    public SortedSet<Integer> changedOrdinals(){return Collections.unmodifiableSortedSet(new TreeSet<>(changes.keySet()));}
    public boolean sharesHeader(NativeBlueprintEdits other){
        return other!=null&&source==other.source&&meta==other.meta&&document==other.document;
    }
    public boolean changed(int rank){return changes.containsKey(rank);}
    public int[] changedSince(NativeBlueprintEdits previous){
        if(this==previous)return new int[0];
        if(previous!=null&&prior!=null&&prior.get()==previous)return touched.clone();
        if(previous!=null&&previous.prior!=null&&previous.prior.get()==this)return previous.touched.clone();
        TreeSet<Integer> ranks=new TreeSet<>(changes.keySet());if(previous!=null)ranks.addAll(previous.changes.keySet());
        return ranks.stream().mapToInt(Integer::intValue).toArray();
    }
    public JsonObject metadata()throws IOException{return NativeBlueprintJson.copy(meta.getAsJsonObject("fields"));}
    public JsonObject header()throws IOException{return source.restoredFields(document,path->path.startsWith("/document/")&&!path.startsWith("/document/palette/")).getAsJsonObject("document");}
    public JsonObject wrapper(int ordinal,long descriptor)throws IOException{
        JsonObject changed=changes.get(ordinal);return changed==null?source.spatial().wrapper(descriptor):NativeBlueprintJson.copy(changed);
    }
    public NativeBlueprintEdits author(String id,String name,String author,String uuid,long created)throws IOException{
        JsonObject changed=NativeBlueprintJson.copy(meta);JsonObject fields=changed.getAsJsonObject("fields");
        fields.addProperty("id",id);fields.addProperty("name",name);
        if(author!=null)fields.addProperty("authorName",author);if(uuid!=null)fields.addProperty("authorUuid",uuid);
        if(created>0)fields.addProperty("createdAt",created);
        return new NativeBlueprintEdits(this,changes,changed,document);
    }
    public NativeBlueprintEdits prompt(String prompt)throws IOException{
        JsonObject changed=NativeBlueprintJson.copy(document);changed.getAsJsonObject("fields").getAsJsonObject("document").addProperty("prompt",prompt);
        return new NativeBlueprintEdits(this,changes,meta,changed);
    }
    public NativeBlueprintEdits annotate(int[] ordinals,String prompt,Boolean excluded)throws IOException{
        need(source.allowsGeometryMutation(),"unknown semantics: cell edit refused");
        TreeMap<Integer,JsonObject> next=new TreeMap<>(changes);
        for(int rank:ordinals){
            var at=source.spatial().atOrdinal(rank);JsonObject original=source.spatial().wrapper(at.descriptor);
            JsonObject value=wrapper(rank,at.descriptor);JsonObject fields=value.getAsJsonObject("fields");
            if(prompt!=null)fields.addProperty("prompt",prompt);if(excluded!=null)fields.addProperty("excluded",excluded);
            if(value.equals(original))next.remove(rank);else next.put(rank,value);
        }
        return new NativeBlueprintEdits(this,next,meta,document,ordinals);
    }
    /** Freeze a native file, copying untouched section bytes as-is. Never decode into a legacy document. */
    public void write(Path destination)throws IOException{
        SortedMap<Integer,byte[]> replace=new TreeMap<>();replace.put(1,NativeBlueprintJson.encode(meta));
        if(source.sectionBytes(9)!=0||document.getAsJsonObject("fields").getAsJsonObject("document").entrySet().size()!=0)replace.put(9,NativeBlueprintJson.encode(document));
        if(!changes.isEmpty())replace.put(7,encodedExtras());
        source.writeReplaced(destination,replace);
    }
    private static final class Key implements Comparable<Key>{
        final byte[] b;Key(byte[] v){b=v;}public int compareTo(Key k){return compare(b,k.b);}
    }
    private byte[] encodedExtras()throws IOException{
        TreeMap<Key,Integer> dict=new TreeMap<>();Map<Integer,TreeMap<Key,Integer>> counts=new TreeMap<>();
        Map<Long,Key> keys=new HashMap<>();Map<Integer,Key> edited=new HashMap<>();
        Key empty=new Key(NativeBlueprintJson.encode(NativeBlueprintDefinition.emptyWrap()));
        for(var e:source.spatial().counts().entrySet()){
            long d=e.getKey();Key key=new Key(NativeBlueprintJson.encode(source.spatial().wrapper(d)));
            keys.put(d,key);counts.computeIfAbsent(NativeBlueprintSpatialView.element(d),unused->new TreeMap<>()).merge(key,e.getValue(),Integer::sum);
        }
        for(var e:changes.entrySet()){
            var cell=source.spatial().atOrdinal(e.getKey());var count=counts.get(NativeBlueprintSpatialView.element(cell.descriptor));
            Key replacement=new Key(NativeBlueprintJson.encode(e.getValue()));count.merge(keys.get(cell.descriptor),-1,Integer::sum);count.merge(replacement,1,Integer::sum);edited.put(e.getKey(),replacement);
        }
        Map<Integer,Key> defaults=new TreeMap<>();
        for(var e:counts.entrySet()){
            int max=0;Key def=empty;
            for(var variant:e.getValue().entrySet())if(variant.getValue()>0){
                if(variant.getKey().compareTo(empty)!=0)dict.put(variant.getKey(),0);
                if(variant.getValue()>max){max=variant.getValue();def=variant.getKey();}
            }
            defaults.put(e.getKey(),def);
        }
        if(dict.isEmpty())return null;
        int id=0;Out out=new Out();out.var(dict.size());for(var e:dict.entrySet()){e.setValue(++id);out.blob(e.getKey().b);}
        int nonempty=0;for(Key def:defaults.values())if(def.compareTo(empty)!=0)nonempty++;
        out.var(nonempty);for(var e:defaults.entrySet())if(e.getValue().compareTo(empty)!=0){out.var(e.getKey());out.var(dict.get(e.getValue()));}
        Out overrides=new Out();int[] written={0};
        var frame=source.frame();source.spatial().spans((y,rank,p,n,d)->{
            Key base=keys.get(d),def=defaults.get(NativeBlueprintSpatialView.element(d));
            if(base.compareTo(def)==0){
                for(int i:changes.subMap(rank,rank+n).keySet())writeOverride(overrides,written,frame,y,p+i-rank,edited.get(i),def,empty,dict);
            }else{
                for(int off=0;off<n;off++)writeOverride(overrides,written,frame,y,p+off,edited.getOrDefault(rank+off,base),def,empty,dict);
            }
        });out.var(written[0]);out.bytes(overrides.toByteArray());return out.toByteArray();
    }
    private static void writeOverride(Out out,int[] written,NativeBlueprintDefinition.Frame f,int y,long p,Key value,Key def,Key empty,Map<Key,Integer> dict)throws IOException{
        if(value.compareTo(def)==0)return;need(out.size()<NativeBlueprintLimits.FILE_BYTES-20,"extra writer budget");
        out.i32((int)(f.minX+p%f.sizeX));out.i32(y);out.i32((int)(f.minZ+p/f.sizeX));out.var(value.compareTo(empty)==0?0:dict.get(value));written[0]++;
    }
}
