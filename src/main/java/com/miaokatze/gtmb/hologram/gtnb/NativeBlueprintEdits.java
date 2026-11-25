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
    private final JsonObject meta,document,regions;
    private final boolean changedRegions;
    private final java.lang.ref.WeakReference<NativeBlueprintEdits> prior;
    private final int[] touched;
    public NativeBlueprintEdits(NativeBlueprintDefinition source)throws IOException{
        this.prior=null;this.touched=new int[0];this.source=source;this.changes=Collections.emptySortedMap();
        this.meta=source.metadata();JsonObject d=source.sectionWrapper(9);
        if(d==null){d=NativeBlueprintDefinition.emptyWrap();JsonObject fields=d.getAsJsonObject("fields");for(String k:new String[]{"document","envelope","legacyEnvelope"})fields.add(k,new JsonObject());}
        this.document=d;this.regions=source.sectionWrapper(8);this.changedRegions=false;
    }
    private NativeBlueprintEdits(NativeBlueprintEdits old,SortedMap<Integer,JsonObject> c,JsonObject m,JsonObject d,int... touched){
        this(old,c,m,d,old.regions,old.changedRegions,touched);
    }
    private NativeBlueprintEdits(NativeBlueprintEdits old,SortedMap<Integer,JsonObject> c,JsonObject m,JsonObject d,
        JsonObject regions,boolean changedRegions,int... touched){
        this.regions=regions;this.changedRegions=changedRegions;
        prior=new java.lang.ref.WeakReference<>(old);this.touched=touched.clone();
        source=old.source;changes=Collections.unmodifiableSortedMap(c);meta=m;document=d;
    }
    public NativeBlueprintDefinition source(){return source;}
    public boolean isUnchanged(){return prior==null;}
    public SortedSet<Integer> changedOrdinals(){return Collections.unmodifiableSortedSet(new TreeSet<>(changes.keySet()));}
    public boolean sharesHeader(NativeBlueprintEdits other){
        return other!=null&&source==other.source&&meta==other.meta&&document==other.document&&regions==other.regions;
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
    JsonObject metadataWrapper()throws IOException{return NativeBlueprintJson.copy(meta);}
    JsonObject documentWrapper()throws IOException{return NativeBlueprintJson.copy(document);}
    public JsonObject regionWrapper()throws IOException{return regions==null?null:NativeBlueprintJson.copy(regions);}
    /** Update only the selected bounding box. Keep every other region and its opaque/native fields unchanged. */
    public NativeBlueprintEdits hintArea(int[] ranks,String prompt)throws IOException{
        need(source.allowsGeometryMutation()&&ranks.length>0,"区域提示缺少有效选择");
        int[] b={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(int rank:ranks){var p=source.spatial().atOrdinal(rank);
            b[0]=Math.min(b[0],p.x);b[1]=Math.min(b[1],p.y);b[2]=Math.min(b[2],p.z);
            b[3]=Math.max(b[3],p.x);b[4]=Math.max(b[4],p.y);b[5]=Math.max(b[5],p.z);}
        JsonObject changed=regions==null?NativeBlueprintDefinition.emptyWrap():regionWrapper();
        var fields=changed.getAsJsonObject("fields");JsonArray before=fields.has("hintAreas")&&!fields.get("hintAreas").isJsonNull()
            ?fields.getAsJsonArray("hintAreas"):new JsonArray(),after=new JsonArray();
        var keys=new String[]{"x1","y1","z1","x2","y2","z2"};JsonObject replacement=null;
        for(JsonElement each:before){JsonObject old=each.getAsJsonObject();boolean same=true;
            for(int i=0;i<6;i++)if((old.has(keys[i])?old.get(keys[i]).getAsInt():0)!=b[i]){same=false;break;}
            if(same)replacement=old;else after.add(old);}
        if(!prompt.isEmpty()){need(after.size()<256,"提示区已达256个上限");
            if(replacement==null)replacement=new JsonObject();for(int i=0;i<6;i++)replacement.addProperty(keys[i],b[i]);
            replacement.addProperty("prompt",prompt);after.add(replacement);}
        fields.add("hintAreas",after);
        return new NativeBlueprintEdits(this,changes,meta,document,changed,true);
    }
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
    /** Rewrite only retention metadata. The original binary NBT pool remains intact even when retention is disabled. */
    public NativeBlueprintEdits retention(boolean changeKeys, List<String> keys, Boolean retain, Boolean covers) throws IOException {
        JsonObject changed=NativeBlueprintJson.copy(document);
        JsonObject h=changed.getAsJsonObject("fields").getAsJsonObject("document");
        if(changeKeys){
            if(keys==null) h.remove("retainedNbtKeys");
            else {JsonArray a=new JsonArray();for(String key:keys)a.add(new JsonPrimitive(key));h.add("retainedNbtKeys",a);}
        }
        if(retain!=null)h.addProperty("retainNbt",retain);
        if(covers!=null)h.addProperty("retainCovers",covers);
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
    /** Immutable clipboard records point at a pinned revision; no item/tile NBT is converted into an ItemStack. */
    public static final class Clip{
        final NativeBlueprintEdits origin;
        final List<NativeBlueprintSpatialView.Point> points=new ArrayList<>();
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        private Clip(NativeBlueprintEdits origin){this.origin=origin;}
        public NativeBlueprintDefinition source(){return origin.source;}
    }
    public Clip copy(int[] ranks)throws IOException{
        need(source.allowsGeometryMutation(),"unsupported geometry semantics");
        Clip clip=new Clip(this);
        for(int rank:ranks){
            var p=source.spatial().atOrdinal(rank);
            protect(p);clip.points.add(p);clip.minX=Math.min(clip.minX,p.x);clip.minY=Math.min(clip.minY,p.y);clip.minZ=Math.min(clip.minZ,p.z);
        }return clip;
    }
    private void protect(NativeBlueprintSpatialView.Point p)throws IOException{
        JsonObject fields=wrapper(p.ordinal,p.descriptor).getAsJsonObject("fields");
        need(!fields.has("controller")||fields.get("controller").isJsonNull()||!fields.get("controller").getAsBoolean(),"控制器始终受保护");
        need(!fields.has("structureId")||fields.get("structureId").isJsonNull()||fields.get("structureId").getAsString().isEmpty(),"所选格关联结构附件，不能只改动几何");
        need(!fields.has("structureIds")||fields.get("structureIds").isJsonNull()||fields.getAsJsonArray("structureIds").size()==0,"所选格关联结构附件，不能只改动几何");
    }
    private static int plus(int coordinate,long delta)throws IOException{
        long result=coordinate+delta;need(result>=Integer.MIN_VALUE&&result<=Integer.MAX_VALUE,"坐标超出范围");return(int)result;
    }
    public NativeBlueprintEdits paste(Clip clip,int anchor,java.util.function.BooleanSupplier current)throws IOException{
        need(clip!=null,"请先复制蓝图格");var p=source.spatial().atOrdinal(anchor);
        return topology(clip,(long)p.x-clip.minX,(long)p.y-clip.minY,(long)p.z-clip.minZ,false,true,false,current);
    }
    public NativeBlueprintEdits moveOrDelete(int[] ranks,int dx,int dy,int dz,boolean copy,boolean delete,
        java.util.function.BooleanSupplier current)throws IOException{
        return topology(copy(ranks),dx,dy,dz,!copy,false,delete,current);
    }
    private NativeBlueprintEdits topology(Clip clip,long dx,long dy,long dz,boolean removeOriginal,boolean replace,boolean delete,
        java.util.function.BooleanSupplier current)throws IOException{
        requireNoLinkedRegions();
        BitSet skipped=new BitSet();
        if(removeOriginal)for(var p:clip.points)skipped.set(p.ordinal);
        if(!delete)for(var point:clip.points){
            var at=source.spatial().atPosition(plus(point.x,dx),plus(point.y,dy),plus(point.z,dz));
            if(at!=null&&!skipped.get(at.ordinal)){need(replace,"目标与其他蓝图格重叠");protect(at);skipped.set(at.ordinal);}
        }
        var f=source.frame();var data=NativeBlueprintMigration.editGeometry(this,sink->{
            source.spatial().spans((y,rank,start,n,descriptor)->{
                need(current.getAsBoolean(),"原生编辑已取消");
                for(int i=0;i<n;i++)if(!skipped.get(rank+i)){long point=start+i;
                    sink.add((int)(f.minX+point%f.sizeX),y,(int)(f.minZ+point/f.sizeX),this,descriptor,rank+i,false);}
            });
            if(!delete)for(var at:clip.points)sink.add(plus(at.x,dx),plus(at.y,dy),plus(at.z,dz),clip.origin,at.descriptor,at.ordinal,true);
        },current);
        return transientEdits(data,current);
    }
    private void requireNoLinkedRegions()throws IOException{
        // The geometry writer cannot rewrite a document-level map containing extra, linked positions.
        // Do not publish a misleading edit where a player-selected anchor changes while its dependent anchor does not.
        var regions=this.regions;
        if(regions!=null)for(var v:regions.getAsJsonObject("fields").entrySet())
            need(v.getValue().isJsonNull()||v.getValue().isJsonArray()&&v.getValue().getAsJsonArray().size()==0,"含位置关联区域的蓝图需连同区域一起修改");
        var header=document.getAsJsonObject("fields").getAsJsonObject("document");
        for(String field:new String[]{"processingNbt","semanticNbt","quantumGroupingNbt","sourceItemFrames","aeIssues"}){
            var value=header.get(field);need(value==null||value.isJsonNull()||value.isJsonPrimitive()&&value.getAsString().isEmpty()
                ||value.isJsonArray()&&value.getAsJsonArray().size()==0,"编辑位置会影响附加数据："+field);
        }
    }
    public interface Rotation {
        /** False for location-sensitive third party adaptors and cross-cell tile references. */
        boolean shareShape(long descriptor,JsonObject wrapper)throws IOException;
        JsonObject delta(long descriptor,JsonObject wrapper,int x,int y,int z)throws IOException;
    }
    /** Reuse the native element/payload interner. Rotate only the selected descriptors, without moving positions. */
    public NativeBlueprintEdits rotateCells(int[] ranks,Rotation transform,java.util.function.BooleanSupplier current)throws IOException{
        need(source.allowsGeometryMutation(),"原生源含未知语义，不修改原件");BitSet selected=new BitSet();
        for(int rank:ranks){var p=source.spatial().atOrdinal(rank);protect(p);selected.set(rank);}
        var f=source.frame();
        return transientEdits(NativeBlueprintMigration.editGeometry(this,sink->source.spatial().spans((y,rank,start,n,descriptor)->{
            need(current.getAsBoolean(),"原生编辑已取消");
            for(int i=0;i<n;i++){int ordinal=rank+i;long pos=start+i;int x=(int)(f.minX+pos%f.sizeX),z=(int)(f.minZ+pos/f.sizeX);
                if(!selected.get(ordinal)){sink.add(x,y,z,this,descriptor,ordinal,false);continue;}
                var w=wrapper(ordinal,descriptor);var delta=transform.delta(descriptor,w,x,y,z);
                if(delta.entrySet().isEmpty())sink.add(x,y,z,this,descriptor,ordinal,false);
                else sink.addAtom(x,y,z,sink.turned(descriptor,w,delta));}
        }),current),current);
    }
    public NativeBlueprintEdits rotate(Rotation transform,java.util.function.BooleanSupplier current)throws IOException{
        need(source.allowsGeometryMutation(),"原生源含未知语义，不修改原件");requireNoLinkedRegions();
        var f=source.frame();need(f.anchorZ!=Integer.MIN_VALUE,"原生锚点旋转溢出");
        var data=NativeBlueprintMigration.editGeometry(this,sink->{sink.turn=true;
            source.spatial().spans((y,rank,start,n,descriptor)->{
                need(current.getAsBoolean(),"原生旋转已取消");
                for(int i=0;i<n;i++){int ordinal=rank+i;long point=start+i;
                    int x=(int)(f.minX+point%f.sizeX),z=(int)(f.minZ+point/f.sizeX);
                    need(z!=Integer.MIN_VALUE,"原生坐标旋转溢出");
                    boolean override=changed(ordinal);var atom=override?null:sink.turnedAtoms.get(descriptor);
                    if(atom==null){JsonObject w=wrapper(ordinal,descriptor);atom=sink.turned(descriptor,w,transform.delta(descriptor,w,x,y,z));
                        if(!override&&transform.shareShape(descriptor,w))sink.turnedAtoms.put(descriptor,atom);}
                    sink.addAtom(-z,y,x,atom);
                }
            });
        },current);
        return transientEdits(data,current);
    }
    private static NativeBlueprintEdits transientEdits(NativeBlueprintMigration.Result data,java.util.function.BooleanSupplier current)throws IOException{
        need(current.getAsBoolean(),"原生编辑已取消");NativeBlueprintDefinition next=NativeBlueprintDefinition.transientDefinition(data.bytes);
        try{need(current.getAsBoolean(),"原生编辑已取消");return new NativeBlueprintEdits(next);}
        catch(IOException|RuntimeException error){next.close();throw error;}
    }

    /** Freeze a native file, copying untouched section bytes as-is. Never decode into a legacy document. */
    public void write(Path destination)throws IOException{
        SortedMap<Integer,byte[]> replace=new TreeMap<>();replace.put(1,NativeBlueprintJson.encode(meta));
        if(source.sectionBytes(9)!=0||document.getAsJsonObject("fields").getAsJsonObject("document").entrySet().size()!=0)replace.put(9,NativeBlueprintJson.encode(document));
        if(changedRegions)replace.put(8,NativeBlueprintJson.encode(regions));
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
