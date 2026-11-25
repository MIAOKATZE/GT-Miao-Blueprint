// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.*;
import net.minecraft.nbt.NBTTagCompound;
import com.miaokatze.gtmb.hologram.HologramNativeBlueprintDraft;
import com.miaokatze.gtmb.hologram.HologramStaticMaterials;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintSpatialView;
import com.miaokatze.gtmb.api.ResourceContainers;

/** The editor/render bridge stores only rank spans per occupied region, not duplicate Cells or XYZ.
 * Construction is deliberately not a consumer of this local-format candidate. */
final class HologramNativeViewport {
    static final class Ranges extends AbstractList<Integer>{
        int[] ends=new int[4],starts=new int[4];int size,ranges;
        void add(int start,int count){
            if(ranges>0&&start==starts[ranges-1]+(ends[ranges-1]-(ranges>1?ends[ranges-2]:0))){size+=count;ends[ranges-1]=size;return;}
            if(ranges==starts.length){starts=Arrays.copyOf(starts,ranges*2);ends=Arrays.copyOf(ends,ranges*2);}
            starts[ranges]=start;ends[ranges++]=(size+=count);
        }
        public Integer get(int i){if(i<0||i>=size)throw new IndexOutOfBoundsException();int r=Arrays.binarySearch(ends,0,ranges,i+1);if(r<0)r=-r-1;return starts[r]+i-(r==0?0:ends[r-1]);}
        public int size(){return size;}
    }
    static final class Index {
        final Map<HologramRegionCache.Key,Ranges> regions=new LinkedHashMap<>();
        final Map<Integer,Map<Long,Integer>> layers=new HashMap<>();
        final Map<Long,Ranges> variants=new LinkedHashMap<>();
        final Map<Long,int[]> extents=new HashMap<>();
        Index(NativeBlueprintSpatialView source,java.util.function.BooleanSupplier current)throws IOException{
            var f=source.definition().frame();
            source.spans((y,rank,p,count,variant)->{
                if(!current.getAsBoolean())throw new IOException("原生蓝图读取已取消");
                long last=p+count-1;
                int z1=(int)(f.minZ+p/f.sizeX),z2=(int)(f.minZ+last/f.sizeX);
                int x1=z1==z2?(int)(f.minX+p%f.sizeX):f.minX,x2=z1==z2?(int)(f.minX+last%f.sizeX):(int)(f.minX+f.sizeX-1);
                grow(extents.computeIfAbsent(variant,k->new int[]{x1,y,z1,x2,y,z2}),x1,y,z1,x2,y,z2);
                layers.computeIfAbsent(y,k->new LinkedHashMap<>()).merge(variant,count,Integer::sum);
                variants.computeIfAbsent(variant,k->new Ranges()).add(rank,count);
                while(count!=0){
                    int x=(int)(f.minX+p%f.sizeX),z=(int)(f.minZ+p/f.sizeX);
                    int n=(int)Math.min(count,Math.min(16-(x&15),f.sizeX-p%f.sizeX));
                    regions.computeIfAbsent(new HologramRegionCache.Key(x>>4,y>>4,z>>4),k->new Ranges()).add(rank,n);
                    rank+=n;p+=n;count-=n;
                }
            });
        }
    }
    private static final class Resolved {
        final HologramState.DescriptorTemplate template;
        final HologramNativeBlueprintDraft.Descriptor source;
        final HologramState.Cell example;
        Resolved(HologramNativeBlueprintDraft.Descriptor d,boolean capacity)throws IOException{
            source=d;HologramBlueprintPreview.completeCell(d.row,capacity);
            template=new HologramState.DescriptorTemplate(d.row,true,
                !d.row.getCompoundTag("targetDisplay").hasKey("nativeTile",10)
                &&!d.row.getCompoundTag("targetStack").hasKey("tag",10),false);
            example=new HologramState.Cell(-1,template,null,true,0,0,0,0,0,0);
        }
    }
    final HologramNativeBlueprintDraft draft;
    final Index index;
    final int[] bounds;
    final Map<String,List<Integer>> memberships;
    final Map<String,int[]> structureBounds;
    final Set<String> missingStructures;
    private static void grow(int[] b,int x1,int y1,int z1,int x2,int y2,int z2){
        b[0]=Math.min(b[0],x1);b[1]=Math.min(b[1],y1);b[2]=Math.min(b[2],z1);
        b[3]=Math.max(b[3],x2);b[4]=Math.max(b[4],y2);b[5]=Math.max(b[5],z2);
    }
    final List<HologramState.Cell> cells;
    final List<HologramState.Cell> ordinaryExamples;
    int materializedCells(){return materialized.get();}
    private final java.util.concurrent.atomic.AtomicInteger materialized=new java.util.concurrent.atomic.AtomicInteger();
    List<HologramState.Cell> annotationCells(){
        List<HologramState.Cell> result=new ArrayList<>();
        // No geometry traversal for unmarked descriptor spans, including all-zero maps.
        for(var entry:index.variants.entrySet()){
            Resolved d=descriptors.get(entry.getKey());
            if(!hasAnnotation(d))continue;
            for(int rank:entry.getValue())if(!edited.containsKey(rank))result.add(cells.get(rank));
        }
        for(var e:edited.entrySet())if(hasAnnotation(e.getValue()))result.add(cells.get(e.getKey()));
        result.sort(Comparator.comparingInt(c->c.index));
        return result;
    }
    private static boolean hasAnnotation(Resolved d){
        if(!d.source.row.getString("prompt").isEmpty())return true;
        return d.example.quantumNetworkHint();
    }
    private final Map<Long,Resolved> descriptors;
    private final Map<Integer,Resolved> edited;
    private final Map<Resolved,Integer> totalAdjust;
    private final Map<Integer,Map<Resolved,Integer>> layerAdjust;
    final NativeBlueprintSpatialView geometry;
    final HologramRegionCache regions;
    private final long providerVersion,resourceVersion;
    private final Map<Integer,HologramLocalMaterialIndex.Result> layerTotals=new HashMap<>();
    private HologramLocalMaterialIndex.Result total;
    private final Map<String,HologramLocalMaterialIndex.Result> families=new HashMap<>();
    private final Map<Integer,HologramState.Cell> cache=Collections.synchronizedMap(new LinkedHashMap<>(128,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Integer,HologramState.Cell> e){return size()>512;}
    });
    HologramNativeViewport(HologramNativeBlueprintDraft draft,Index index,NBTTagCompound meta,HologramNativeViewport previous)throws IOException{
        this(draft,index,meta,previous,()->true);
    }
    HologramNativeViewport(HologramNativeBlueprintDraft draft,Index index,NBTTagCompound meta,HologramNativeViewport previous,
        java.util.function.BooleanSupplier current)throws IOException{
        this.draft=draft;this.index=index;geometry=draft.space();
        providerVersion=HologramStaticMaterials.nativeGeneration();resourceVersion=ResourceContainers.generation();
        boolean share=previous!=null&&previous.geometry==geometry&&previous.currentProviders();
        descriptors= share?previous.descriptors:new HashMap<>();
        boolean capacity=HologramBlueprintPreview.showMeOutputCapacity(meta);
        if(!share)for(long d:geometry.counts().keySet()){
            if(!current.getAsBoolean())throw new IOException("原生蓝图读取已取消");
            descriptors.put(d,new Resolved(draft.resolve(d,geometry.wrapper(d)),capacity));
        }
        var f=draft.revision().source().frame();
        bounds=new int[]{f.minX,f.minY,f.minZ,(int)(f.minX+f.sizeX-1),(int)(f.minY+f.sizeY-1),(int)(f.minZ+f.sizeZ-1)};
        int[] changes=draft.revision().changedSince(share?previous.draft.revision():null);
        edited=share?new HashMap<>(previous.edited):new HashMap<>();
        totalAdjust=share?new HashMap<>(previous.totalAdjust):new LinkedHashMap<>();
        layerAdjust=share?new HashMap<>(previous.layerAdjust):new HashMap<>();
        Set<Integer> dirtyLayers=new HashSet<>();
        // Only the affected ranks go through provider resolution. A metadata change has no changed ranks.
        for(int rank:changes){
            if(!current.getAsBoolean())throw new IOException("原生蓝图编辑已取消");
            var p=geometry.atOrdinal(rank);Resolved base=descriptors.get(p.descriptor),before=edited.getOrDefault(rank,base);
            Resolved after=base;
            if(draft.revision().changed(rank)){after=new Resolved(draft.resolve(p.descriptor,draft.revision().wrapper(rank,p.descriptor)),capacity);edited.put(rank,after);}else edited.remove(rank);
            if(dirtyLayers.add(p.y))layerAdjust.put(p.y,new HashMap<>(layerAdjust.getOrDefault(p.y,Collections.emptyMap())));
            bump(layerAdjust.get(p.y),before,-1);bump(layerAdjust.get(p.y),after,1);
            bump(totalAdjust,before,-1);bump(totalAdjust,after,1);
        }
        List<HologramState.Cell> examples=new ArrayList<>();
        for(Resolved d:descriptors.values())if(d.template.plainRenderExample()&&examples.size()<64)examples.add(d.example);
        for(Resolved d:edited.values())if(d.template.plainRenderExample()&&examples.size()<64)examples.add(d.example);
        ordinaryExamples=Collections.unmodifiableList(examples);
        cells=new AbstractList<>(){
            public HologramState.Cell get(int i){
                var result=cache.get(i);if(result!=null)return result;
                try{var p=geometry.atOrdinal(i);Resolved d=edited.getOrDefault(i,descriptors.get(p.descriptor));
                    result=new HologramState.Cell(i,d.template,null,true,p.x,p.y,p.z,p.x,p.y,p.z);cache.put(i,result);materialized.incrementAndGet();return result;
                }catch(IOException e){throw new IllegalStateException("无法读取原生蓝图页",e);}
            }
            public int size(){return geometry.size();}
        };
        // No region walk or adapter allocation at revision change. Each visited chunk lazily supplies
        // a view onto the same envelope and this revision's bounded cell cache.
        Map<HologramRegionCache.Key,List<HologramState.Cell>> bounded=new AbstractMap<>(){
            private List<HologramState.Cell> view(Ranges r){return r==null?null:new AbstractList<>(){
                public int size(){return r.size();}public HologramState.Cell get(int i){return cells.get(r.get(i));}
            };}
            @Override public int size(){return index.regions.size();}
            @Override public List<HologramState.Cell> get(Object key){return view(index.regions.get(key));}
            @Override public Set<Entry<HologramRegionCache.Key,List<HologramState.Cell>>> entrySet(){return new AbstractSet<>(){
                public int size(){return index.regions.size();}
                public Iterator<Entry<HologramRegionCache.Key,List<HologramState.Cell>>> iterator(){
                    var source=index.regions.entrySet().iterator();return new Iterator<>(){
                        public boolean hasNext(){return source.hasNext();}
                        public Entry<HologramRegionCache.Key,List<HologramState.Cell>> next(){var r=source.next();return new SimpleImmutableEntry<>(r.getKey(),view(r.getValue()));}
                    };}
            };}
        };
        regions=new HologramRegionCache(bounded,bounds,(x,y,z)->{
            try{var p=geometry.atPosition(x,y,z);return p==null?null:cells.get(p.ordinal);}
            catch(IOException e){throw new IllegalStateException(e);}
        });
        // Membership is immutable for the supported sparse flag/prompt edits; keep a descriptor-span union.
        if(share){memberships=previous.memberships;structureBounds=previous.structureBounds;missingStructures=previous.missingStructures;}else{
            Map<String,int[]> boundsByGroup=new LinkedHashMap<>();Set<String> missing=new HashSet<>();
            Map<String,List<Ranges>> groups=new LinkedHashMap<>();
            for(var e:index.variants.entrySet()){
                List<String> ids=descriptors.get(e.getKey()).source.memberships;if(ids.isEmpty())ids=Collections.singletonList("");
                for(String id:ids){
                    groups.computeIfAbsent(id,k->new ArrayList<>()).add(e.getValue());
                    if(id.isEmpty())continue;
                    var ex=index.extents.get(e.getKey());
                    grow(boundsByGroup.computeIfAbsent(id,k->ex.clone()),ex[0],ex[1],ex[2],ex[3],ex[4],ex[5]);
                    if(descriptors.get(e.getKey()).source.row.getBoolean("missingBlock"))missing.add(id);
                }
            }
            Map<String,List<Integer>> members=new LinkedHashMap<>();
            groups.forEach((k,rs)->{if(rs.size()==1)members.put(k,rs.get(0));else{
                // Merge spans, not XYZ or member objects; maintain the original ascending selection order.
                List<int[]> spans=new ArrayList<>();for(Ranges r:rs)for(int i=0;i<r.ranges;i++)spans.add(new int[]{r.starts[i],r.ends[i]-(i==0?0:r.ends[i-1])});
                spans.sort(Comparator.comparingInt(a->a[0]));Ranges out=new Ranges();for(int[] s:spans)out.add(s[0],s[1]);members.put(k,out);}});
            memberships=Collections.unmodifiableMap(members);structureBounds=Collections.unmodifiableMap(boundsByGroup);missingStructures=Collections.unmodifiableSet(missing);
        }
        if(share){layerTotals.putAll(previous.layerTotals);families.putAll(previous.families);}
        if(share&&changes.length==0){total=previous.total;return;}
        if(share)for(int y:dirtyLayers)layerTotals.put(y,fold(index.layers.get(y),layerAdjust.get(y)));
        else for(var e:index.layers.entrySet())layerTotals.put(e.getKey(),fold(e.getValue(),layerAdjust.get(e.getKey())));
        total=fold(geometry.counts(),totalAdjust);
        Map<String,Map<Resolved,Integer>> byFamily=new HashMap<>();
        geometry.counts().forEach((k,n)->{Resolved d=descriptors.get(k);String fName=d.source.row.getString("family");if(!fName.isEmpty())byFamily.computeIfAbsent(fName,unused->new HashMap<>()).merge(d,n,Integer::sum);});
        totalAdjust.forEach((d,n)->{String fName=d.source.row.getString("family");if(!fName.isEmpty())byFamily.computeIfAbsent(fName,unused->new HashMap<>()).merge(d,n,Integer::sum);});
        byFamily.forEach((k,units)->families.put(k,sum(units)));
    }
    private static void bump(Map<Resolved,Integer> tally,Resolved target,int amount){
        int n=tally.getOrDefault(target,0)+amount;if(n==0)tally.remove(target);else tally.put(target,n);
    }
    boolean currentProviders(){return providerVersion==HologramStaticMaterials.nativeGeneration()&&resourceVersion==ResourceContainers.generation();}
    private HologramLocalMaterialIndex.Result fold(Map<Long,Integer> counts,Map<Resolved,Integer> delta){
        Map<Resolved,Integer> units=new LinkedHashMap<>();counts.forEach((k,n)->units.merge(descriptors.get(k),n,Integer::sum));
        if(delta!=null)delta.forEach((d,n)->units.merge(d,n,Integer::sum));return sum(units);
    }
    private static HologramLocalMaterialIndex.Result sum(Map<Resolved,Integer> units){
        var b=new HologramStaticMaterials.Builder();int n=0;for(var e:units.entrySet())if(e.getKey().source.included&&e.getValue()!=0){
            b.addResolved(e.getKey().source.contribution,e.getValue());n+=e.getValue();
        }return new HologramLocalMaterialIndex.Result(b.snapshot(),n);
    }
    HologramLocalMaterialIndex.Result material(int scope,int y,int selected,int[] selection){
        if(!currentProviders())return null;if(scope==0)return total;if(scope==1)return layerTotals.getOrDefault(y,EMPTY);
        if(scope==3)return selected<0||selected>=cells.size()?EMPTY:families.getOrDefault(cells.get(selected).family,EMPTY);
        if(scope==2&&selection.length<=1){int i=selection.length==1?selection[0]:selected;if(i<0||i>=cells.size())return EMPTY;
            try{var p=geometry.atOrdinal(i);return sum(Collections.singletonMap(edited.getOrDefault(i,descriptors.get(p.descriptor)),1));}
            catch(IOException e){return null;}}
        return null; // Do not substitute whole-file totals for a scope that has not been prepared.
    }
    final class Selection {
        private final Map<Resolved,Integer> units=new LinkedHashMap<>();
        void change(int ordinal,int delta){
            try{var p=geometry.atOrdinal(ordinal);Resolved d=edited.getOrDefault(ordinal,descriptors.get(p.descriptor));
                int n=units.getOrDefault(d,0)+delta;if(n<0)throw new IllegalStateException("unbalanced selection");if(n==0)units.remove(d);else units.put(d,n);
            }catch(IOException e){throw new IllegalStateException("无法读取原生选择的材料",e);}
        }
        void clear(){units.clear();}HologramLocalMaterialIndex.Result result(){return sum(units);}
    }
    private static final HologramLocalMaterialIndex.Result EMPTY=sum(Collections.emptyMap());
}
