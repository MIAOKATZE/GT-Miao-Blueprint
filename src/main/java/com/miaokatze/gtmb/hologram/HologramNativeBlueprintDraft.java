// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import com.google.gson.*;
import com.miaokatze.gtmb.hologram.gtnb.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Local authoring bridge. The DTOs here are a geometry-free UI header and one descriptor,
 * never a second copy of a blueprint. Registry/resource resolution runs only on the client owner. */
public final class HologramNativeBlueprintDraft {
    private static final Gson GSON=new Gson();
    private final NativeBlueprintEdits revision;
    private final HologramPresets.Preset header;
    private final Payloads payloads;
    private static final class Payloads {
        private static final int MAX_CHARGE=8<<20;
        private final Map<Integer,NBTTagCompound> cache=new LinkedHashMap<>(16,.75f,true);
        private final Map<Integer,Integer> charges=new HashMap<>();
        private int total,decodes;
        synchronized NBTTagCompound get(NativeBlueprintDefinition source,int id)throws IOException{
            if(id==0)return null;
            NBTTagCompound found=cache.get(id);if(found!=null)return found;
            byte[] bytes=source.payload(id);
            var nbt=net.minecraft.nbt.CompressedStreamTools.func_152456_a(
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)),new net.minecraft.nbt.NBTSizeTracker(16L<<20));
            decodes++;
            // Charge a conservative multiple of the encoded payload plus map/node overhead.
            int charge=Math.toIntExact((long)bytes.length*4+256);
            if(charge<=MAX_CHARGE){
                while(total+charge>MAX_CHARGE&&!cache.isEmpty()){
                    var it=cache.keySet().iterator();total-=charges.remove(it.next());it.remove();
                }
                cache.put(id,nbt);charges.put(id,charge);total+=charge;
            }
            return nbt;
        }
        synchronized int decodes(){return decodes;}
    }
    public int payloadDecodeCount(){return payloads.decodes();}
    /** Called on the existing detached I/O worker; parsing raw NBT never resolves a registry or provider. */
    public void preparePayloads(java.util.function.BooleanSupplier current)throws IOException{
        var source=revision.source();
        Set<Integer> seen=new HashSet<>();
        for(long descriptor:space().counts().keySet()){
            if(!current.getAsBoolean())throw new IOException("原生蓝图读取已取消");
            var wrapper=space().wrapper(descriptor);
            for(String key:new String[]{"tileNbt","itemNbt"}){
                int id=source.payloadRef(wrapper,key);
                if(id!=0&&seen.add(id))payloads.get(source,id);
            }
        }
    }
    public HologramNativeBlueprintDraft(NativeBlueprintEdits revision)throws IOException{
        this(revision,null);
    }
    HologramNativeBlueprintDraft(NativeBlueprintEdits revision,HologramNativeBlueprintDraft previous)throws IOException{
        this.revision=revision;
        payloads=previous!=null&&previous.revision.source()==revision.source()?previous.payloads:new Payloads();
        if(previous!=null&&revision.sharesHeader(previous.revision)){header=previous.header;return;}
        NativeBlueprintDefinition def=revision.source();
        JsonObject header=revision.header();
        for(var entry:revision.metadata().entrySet())header.add(entry.getKey(),entry.getValue());
        JsonObject regions=revision.regionWrapper();
        if(regions!=null)for(var row:def.restoredFields(regions).entrySet())header.add(row.getKey(),row.getValue());
        // This projection is UI-only. Original unknowns, payloads, nullable values, and palette remain in the definition.
        header.remove("palette");
        try{this.header=GSON.fromJson(header,HologramPresets.Preset.class);}catch(RuntimeException e){throw new IOException("无法解释原生蓝图头部；原件未修改",e);}
        if(this.header.structures==null)this.header.structures=Collections.emptyList();
        if(this.header.hintAreas==null)this.header.hintAreas=Collections.emptyList();
    }
    HologramPresets.Preset header(){return header;}
    NativeBlueprintEdits rotateCells(int[] ranks,boolean vertical,java.util.function.BooleanSupplier current)throws IOException{
        return revision.rotateCells(ranks,new NativeBlueprintEdits.Rotation(){
            public boolean shareShape(long d,JsonObject w){return false;}
            public JsonObject delta(long d,JsonObject w,int x,int y,int z)throws IOException{
                var c=cell(d,w,true);c.x=x;c.y=y;c.z=z;
                var payload=payloads.get(revision.source(),revision.source().payloadRef(w,"tileNbt"));
                if(c.missing()||HologramBlockTransformLittleTiles.needsNeighbours(c.block(),payload))throw new IOException("未知方块或跨格小方块请使用整图旋转");
                int metadata=c.metadata,facing=c.facing;String tile=c.tileNbt,item=c.itemNbt;
                HologramLocalBlueprintDocument.rotateCell(c,vertical);
                JsonObject out=new JsonObject();if(c.metadata!=metadata)out.addProperty("metadata",c.metadata);
                if(c.facing!=facing)out.addProperty("facing",c.facing);if(!Objects.equals(tile,c.tileNbt))out.addProperty("tileNbt",c.tileNbt);
                if(!Objects.equals(item,c.itemNbt))out.addProperty("itemNbt",c.itemNbt);return out;
            }
        },current);
    }
    NativeBlueprintEdits rotate(java.util.function.BooleanSupplier current)throws IOException{
        // Gather only coordinate-dependent LittleTiles references, and only when such a descriptor exists.
        // Ordinary diagrams go straight to the native writer with no preliminary occupied-cell pass.
        Map<Long,NBTTagCompound> linked=new HashMap<>();
        for(long d:space().counts().keySet()){
            var fields=space().wrapper(d);int ref=revision.source().payloadRef(fields,"tileNbt");
            if(ref!=0){var tag=payloads.get(revision.source(),ref);if(tag!=null&&tag.hasKey("tilesCount"))linked.put(d,tag);}
        }
        Map<String,int[]> references=new HashMap<>();var frame=revision.source().frame();
        if(!linked.isEmpty())space().spans((y,rank,start,n,d)->{
            if(!current.getAsBoolean())throw new IOException("原生旋转已取消");var tag=linked.get(d);if(tag==null)return;
            for(int i=0;i<n;i++){long p=start+i;HologramBlockTransformLittleTiles.references(references,tag,
                (int)(frame.minX+p%frame.sizeX),y,(int)(frame.minZ+p/frame.sizeX),false);}
        });
        return revision.rotate(new NativeBlueprintEdits.Rotation(){
            public boolean shareShape(long d,JsonObject w)throws IOException{
                var c=placementShape(d,w);
                return c.block!=null&&c.block.startsWith("minecraft:")&&revision.source().payloadRef(w,"tileNbt")==0
                    &&revision.source().payloadRef(w,"itemNbt")==0;
            }
            public JsonObject delta(long d,JsonObject w,int x,int y,int z)throws IOException{
                var c=cell(d,w,true);c.x=x;c.y=y;c.z=z;
                int metadata=c.metadata,facing=c.facing;String tile=c.tileNbt,item=c.itemNbt;
                if(c.missing())throw new IOException("未知方块不能安全旋转；原始文件未修改");
                if(header.schema>=2)HologramBlockTransforms.turn(c,false,references,true);
                c.facing=HologramBlockTransforms.isPipe(c.stack())?-1:HologramVirtualTransform.front(facing,false);
                var delta=new JsonObject();
                if(c.metadata!=metadata)delta.addProperty("metadata",c.metadata);
                if(c.facing!=facing)delta.addProperty("facing",c.facing);
                if(!Objects.equals(tile,c.tileNbt))delta.addProperty("tileNbt",c.tileNbt);
                if(!Objects.equals(item,c.itemNbt))delta.addProperty("itemNbt",c.itemNbt);
                return delta;
            }
        },current);
    }
    public boolean sameMaterialPolicy(HologramNativeBlueprintDraft other){
        return other!=null&&header.retainNbt==other.header.retainNbt&&header.retainCovers==other.header.retainCovers
            &&java.util.Objects.equals(header.retainedNbtKeys,other.header.retainedNbtKeys);
    }
    public NativeBlueprintEdits revision(){return revision;}
    public NativeBlueprintSpatialView space(){return revision.source().spatial();}
    HologramPresets.Cell cell(long descriptor,JsonObject wrap)throws IOException{return cell(descriptor,wrap,true);}
    private HologramPresets.Cell cell(long descriptor,JsonObject wrap,boolean includeNativePayload)throws IOException{
        JsonObject value=revision.source().element(NativeBlueprintSpatialView.element(descriptor));
        for(var e:revision.source().restoredFields(wrap,key->includeNativePayload||(!key.equals("/tileNbt")&&!key.equals("/itemNbt"))).entrySet())value.add(e.getKey(),e.getValue());
        // Gson coerces numeric strings and booleans. That would fabricate payable material or
        // silently discard unknown NBT contents. The original file is retained on refusal.
        for(String k:new String[]{"block","item","defaultItem","tileNbt","itemNbt","prompt","structureId",
            "structureRole","structureFamily","structureHint"}) {
            var v=value.get(k);if(v!=null&&!v.isJsonNull()&&(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()))throw new IOException("原生方块含不支持的字段类型："+k);
        }
        for(String k:new String[]{"damage","count","metadata","defaultDamage","facing"}){
            var v=value.get(k);if(v!=null&&!v.isJsonNull())try{
                if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new NumberFormatException();
                new java.math.BigDecimal(v.getAsString()).intValueExact();
            }catch(ArithmeticException|NumberFormatException invalid){throw new IOException("原生方块含不支持的整数："+k);}
        }
        for(String k:new String[]{"controller","excluded","structureCandidatesComplete"}){
            var v=value.get(k);if(v!=null&&!v.isJsonNull()&&(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean()))throw new IOException("原生方块含不支持的标记："+k);
        }
        try{return GSON.fromJson(value,HologramPresets.Cell.class);}catch(RuntimeException e){throw new IOException("无法解释元素 "+NativeBlueprintSpatialView.element(descriptor)+"；未按普通方块代替",e);}
    }
    HologramPresets.Cell placementShape(long descriptor, JsonObject wrapper) throws IOException {
        return cell(descriptor, wrapper, false);
    }

    HologramPresets.Cell cell(int rank)throws IOException{
        var at=space().atOrdinal(rank);var result=cell(at.descriptor,revision.wrapper(rank,at.descriptor));result.x=at.x;result.y=at.y;result.z=at.z;return result;
    }
    public static final class Descriptor {
        public final NBTTagCompound row;
        public final HologramStaticMaterials.Contribution contribution;
        public final boolean included;
        public final List<String> memberships;
        Descriptor(NBTTagCompound row,HologramStaticMaterials.Contribution c,boolean i,List<String> m){this.row=row;contribution=c;included=i;memberships=m;}
    }
    /** Read typed source payload through the same retention policy as the material bill. */
    HologramPresets.Cell executionCell(long descriptor, JsonObject wrapper) throws IOException {
        var cell=placementShape(descriptor,wrapper);
        var source=revision.source();
        var block=cell.block();
        var keys=header.retainNbt?header.retainedNbtKeys:Collections.<String>emptyList();
        var tile=HologramBlueprintNbt.resolveNativePayload(payloads.get(source,source.payloadRef(wrapper,"tileNbt")));
        var item=HologramBlueprintNbt.resolveNativePayload(payloads.get(source,source.payloadRef(wrapper,"itemNbt")));
        if(tile!=null)tile=HologramBlueprintNbt.filterSelection(block,tile,keys,header.retainCovers);
        if(item!=null)item=HologramBlueprintNbt.filterSelection(item,keys,header.retainCovers);
        cell.tileNbt=tile==null||tile.hasNoTags()?"":HologramBlueprintNbt.encodeCanonical(block,tile);
        cell.itemNbt=item==null||item.hasNoTags()?"":HologramBlueprintNbt.encodeCanonical(item);
        return cell;
    }

    /** One full source descriptor (including retained tile/item payload), not its render appearance. */
    public Descriptor resolve(long descriptor,JsonObject wrap)throws IOException{
        var cell=cell(descriptor,wrap,false);
        var source=revision.source();
        NBTTagCompound rawTile=payloads.get(source,source.payloadRef(wrap,"tileNbt"));
        NBTTagCompound rawItem=payloads.get(source,source.payloadRef(wrap,"itemNbt"));
        var row=HologramLocalBlueprintDocument.nativeDisplayDescriptor(cell,rawTile,rawItem);
        HologramStaticMaterials.Builder bill=new HologramStaticMaterials.Builder();
        boolean included=!cell.excluded&&!"minecraft:air".equals(cell.block);
        if(included)try{
            if(!revision.source().allowsGeometryMutation()||cell.count!=1||cell.missing())throw new IOException("未解析或不支持的原生材料");
            ItemStack item=cell.stack();Block block=(cell.block==null||cell.block.isEmpty())&&item!=null?Block.getBlockFromItem(item.getItem()):cell.block();
            if(item==null||block==null||block==Blocks.air)throw new IOException("未解析的方块或物品");
            NBTTagCompound tile=HologramBlueprintNbt.resolveNativePayload(rawTile),it=HologramBlueprintNbt.resolveNativePayload(rawItem);
            List<String> keys=header.retainNbt?header.retainedNbtKeys:Collections.emptyList();
            if(tile!=null)tile=HologramBlueprintNbt.filterSelection(block,tile,keys,header.retainCovers);
            if(it!=null)it=HologramBlueprintNbt.filterSelection(it,keys,header.retainCovers);
            int bindingGroup=HologramSavedBindingChoices.requiredGroup(tile);
            if(bindingGroup!=0)row.setInteger("savedBindingGroup",bindingGroup);
            if(it!=null)item.setTagCompound(it);
            bill.add(block,row.getInteger("wantMeta"),item,tile,it);
        }catch(IOException|RuntimeException|LinkageError failure){
            bill=new HologramStaticMaterials.Builder();bill.unaccounted("部分保存的方块、物品或附加资源无法在本机核算");
        }
        return new Descriptor(row,bill.freeze(),included,Collections.unmodifiableList(cell.memberships()));
    }
}
