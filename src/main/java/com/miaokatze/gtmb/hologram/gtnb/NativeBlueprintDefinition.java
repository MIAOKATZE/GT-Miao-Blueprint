// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.*;

/** An owned read-only native definition. Geometry cursors hold at most one bounded page, never a full XYZ table. */
public final class NativeBlueprintDefinition implements Closeable {
    static final byte[] MAGIC={'G','T','M','B','N','B','P',0};
    private final FileChannel file;
    private final Map<Integer,Section> sections=new java.util.TreeMap<>();
    private final List<byte[]> elements=new ArrayList<>(), extraDictionary=new ArrayList<>();
    final Map<Integer,Integer> defaults=new HashMap<>();
    private final Map<Integer,int[]> sourceSyntax=new HashMap<>();
    final Map<Position,Integer> overrides=new java.util.LinkedHashMap<>();
    final List<Layer> layers=new ArrayList<>();
    final List<Page> pages=new ArrayList<>();
    private final List<Payload> payloads=new ArrayList<>();
    private final Set<Integer> referencedPayloads=new HashSet<>();
    private JsonObject metadata;
    private Frame frame;
    private long occupied;
    private boolean unsupported;
    private final String revision;

    private NativeBlueprintDefinition(Path path)throws IOException {
        file=FileChannel.open(path,StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        try { readHeader(); readElements();readIndex();readPayloads();readExtras();validateWrappers();validateGeometry();
            need(referencedPayloads.size()==payloads.size(),"unreferenced payload");
            // Exact revision is a file identity, not a server capability.
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long at=0;while(at<file.size()){byte[] b=read(file,at,(int)Math.min(65536,file.size()-at));digest.update(b);at+=b.length;}StringBuilder h=new StringBuilder();for(byte b:digest.digest())h.append(String.format("%02x",b&255));revision=h.toString();
        }catch(IOException|RuntimeException|java.security.NoSuchAlgorithmException e){file.close();if(e instanceof IOException)throw(IOException)e;throw new IOException("Native blueprint decode",e);}
    }
    public static NativeBlueprintDefinition open(Path path)throws IOException{return new NativeBlueprintDefinition(path);}
    public static boolean hasMagic(Path path)throws IOException {
        try(FileChannel f=FileChannel.open(path,StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            return f.size()>=MAGIC.length && Arrays.equals(read(f,0,MAGIC.length),MAGIC);
        }
    }
    public String revision(){return revision;}
    public long cells(){return occupied;}
    public Frame frame(){return frame;}
    public int elementCount(){return elements.size();}
    public JsonObject metadata()throws IOException{return NativeBlueprintJson.copy(metadata);}
    public boolean allowsGeometryMutation(){return !unsupported;}
    public JsonObject element(int id)throws IOException{need(id>=1&&id<=elements.size(),"element id");return NativeBlueprintJson.parse(Arrays.copyOfRange(elements.get(id-1),1,elements.get(id-1).length)).getAsJsonObject();}
    public JsonObject sectionWrapper(int type)throws IOException{Section s=sections.get(type);return s==null?null:NativeBlueprintJson.parse(bytes(s)).getAsJsonObject();}
    public List<Integer> layerYs(){List<Integer> y=new ArrayList<>();for(Layer l:layers)y.add(l.y);return Collections.unmodifiableList(y);}
    public RunCursor layerCursor(int y)throws IOException{return new RunCursor(findLayer(y));}
    Layer findLayer(int y){int low=0,high=layers.size()-1;while(low<=high){int mid=(low+high)>>>1;Layer l=layers.get(mid);if(l.y<y)low=mid+1;else if(l.y>y)high=mid-1;else return l;}return null;}
    public int elementAt(int x,int y,int z)throws IOException {if(!frame.contains(x,y,z))return 0;Layer l=findLayer(y);if(l==null)return 0;long p=frame.position(x,z);int low=l.first,high=l.first+l.pages-1;while(low<=high){int mid=(low+high)>>>1;Page page=pages.get(mid);if(p<page.start)high=mid-1;else if(p>=page.end)low=mid+1;else{try(RunCursor c=new RunCursor(new Layer(y,mid,1,page.records,page.cells))){while(c.next()){if(c.start()>p)return 0;if(p-c.start()<c.length())return c.element();}}return 0;}}return 0;}

    private NativeBlueprintSpatialView spatial;
    public NativeBlueprintSpatialView spatial(){return spatial;}
    int extraIdAt(int x,int y,int z,int element){return overrides.getOrDefault(new Position(x,y,z),defaults.getOrDefault(element,0));}
    JsonObject extraWrapper(int id)throws IOException {return id==0?emptyWrap():NativeBlueprintJson.parse(extraDictionary.get(id-1)).getAsJsonObject();}
    public JsonObject extras(int x,int y,int z,int element)throws IOException {return extraWrapper(extraIdAt(x,y,z,element));}
    public int payloadRef(JsonObject wrapper,String key)throws IOException{
        if(!wrapper.has("nbt"))return 0;
        for(JsonElement e:wrapper.getAsJsonArray("nbt")){
            JsonArray ref=e.getAsJsonArray();
            if(ref.get(0).getAsString().equals("/"+key))return integer(ref.get(1));
        }
        return 0;
    }
    public byte[] payload(int id)throws IOException {need(id>0&&id<=payloads.size(),"payload id");Payload p=payloads.get(id-1);byte[] b=read(file,sections.get(6).offset+p.offset,p.stored);return p.codec==0?b:NativeBlueprintNbt.gunzip(b,p.expanded);}
    byte[] rawSection(int type)throws IOException {return bytes(sections.get(type));}
    List<Section> extensions(){List<Section> result=new ArrayList<>();for(Section s:sections.values())if(Integer.compareUnsigned(s.type,10)>0)result.add(s);result.sort((a,b)->Integer.compareUnsigned(a.type,b.type));return result;}
    int payloadCount(){return payloads.size();}
    int payloadLength(int id){return payloads.get(id-1).expanded;}
    public void copyTo(java.io.OutputStream output)throws IOException {long at=0;while(at<file.size()){byte[] b=read(file,at,(int)Math.min(65536,file.size()-at));output.write(b);at+=b.length;}}
    void writeReplaced(Path target,java.util.SortedMap<Integer,byte[]> replace)throws IOException {
        Map<Integer,Section> chosen=new java.util.TreeMap<>(Integer::compareUnsigned);chosen.putAll(sections);
        for(var e:replace.entrySet())if(e.getValue()==null)chosen.remove(e.getKey());else{
            int t=e.getKey(),n=e.getValue().length;chosen.put(t,new Section(t,t==1?1:3,0,n,n,0,1));}
        int count=chosen.size();long offset=64+40L*count,total=offset;for(Section s:chosen.values())total+=s.size;
        need(total<=NativeBlueprintLimits.FILE_BYTES,"edited native source exceeds limit");
        Out head=new Out();head.bytes(MAGIC);head.u16(1);head.u16(0);head.u32(64);head.u32(0);head.u32(count);head.u64(64);head.u64(total);head.bytes(new byte[24]);
        for(Section s:chosen.values()){head.i32(s.type);head.u32(s.flags);head.u64(offset);head.u64(s.size);head.u64(s.expanded);head.i32(s.codec);head.i32(s.version);offset+=s.size;}
        try(java.io.OutputStream out=java.nio.file.Files.newOutputStream(target,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            head.writeTo(out);for(Section s:chosen.values())if(replace.containsKey(s.type))out.write(replace.get(s.type));else{
                for(int n=0;n<s.size;){int chunk=Math.min(65536,s.size-n);out.write(read(file,s.offset+n,chunk));n+=chunk;}
            }
        }
    }
    public JsonObject restoredFields(JsonObject wrapper)throws IOException {return restoredFields(wrapper,path->true);}
    public JsonObject restoredFields(JsonObject wrapper,java.util.function.Predicate<String> include)throws IOException {
        JsonObject result=NativeBlueprintJson.copy(wrapper.getAsJsonObject("fields"));
        if(wrapper.has("nbt"))for(JsonElement e:wrapper.getAsJsonArray("nbt")){
            JsonArray pair=e.getAsJsonArray();String pointer=pair.get(0).getAsString();
            if(!include.test(pointer))continue;
            java.io.ByteArrayOutputStream zipped=new java.io.ByteArrayOutputStream();
            try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(zipped)){gzip.write(payload(integer(pair.get(1))));}
            pointerParent(result,pointer).addProperty(pointerKey(pointer),java.util.Base64.getEncoder().encodeToString(zipped.toByteArray()));
        }return result;
    }
    public long sectionBytes(int type){Section s=sections.get(type);return s==null?0:s.size;}
    public void close()throws IOException{file.close();}
    static JsonObject emptyWrap(){JsonObject o=new JsonObject();o.add("fields",new JsonObject());return o;}

    private void readHeader()throws IOException {
        long length=file.size();need(length>=64&&length<=NativeBlueprintLimits.FILE_BYTES,"file size");In h=new In(read(file,0,64));need(Arrays.equals(h.take(8),MAGIC),"magic");need(h.u16()==1&&h.u16()==0&&h.u32()==64&&h.u32()==0,"unsupported header");int n=(int)h.u32();need(n>=4&&n<=NativeBlueprintLimits.SECTIONS,"section count");need(h.u64()==64&&h.u64()==length,"file length/directory");for(byte b:h.take(24))need(b==0,"header reserved");
        In d=new In(read(file,64,n*40));long end=64L+n*40,last=0,total=0;
        for(int i=0;i<n;i++){long type=d.u32();int flags=d.i32();long off=d.u64(),size=d.u64(),expanded=d.u64();int codec=d.i32(),version=d.i32();need(type>last&&off==end&&size<=length-off,"section ordering/bounds");last=type;end+=size;need(expanded<=NativeBlueprintLimits.LOGICAL_BYTES-total,"logical budget");total+=expanded;
            if(type<=10){int wanted=type==3||type==4||type==7||type==8||type==9||type==10?3:1;need(flags==wanted&&codec==0&&version==1&&size==expanded,"known section descriptor");}
            else {unsupported=true;need((flags&1)==0,"unsupported required section");}
            sections.put((int)type,new Section((int)type,flags,off,(int)size,expanded,codec,version));}
        need(end==length&&sections.keySet().containsAll(Arrays.asList(1,2,3,4)),"missing/trailing section");
        need(sections.get(1).size<=1024*1024,"metadata budget");for(int t:new int[]{8,9})if(sections.containsKey(t))need(sections.get(t).size<=32*1024*1024,"document section budget");byte[] meta=bytes(sections.get(1));long jsonBytes=0;for(int t:new int[]{1,2,7,8,9})if(sections.containsKey(t))jsonBytes+=sections.get(t).size;need(jsonBytes<=NativeBlueprintLimits.JSON_BYTES,"aggregate JSON budget");metadata=objectCanonical(meta);need(metadata.has("fields")&&metadata.get("fields").isJsonObject(),"metadata fields");unsupported|=!NativeBlueprintSemantics.metaKnown(metadata.getAsJsonObject("fields"));for(String k:keys(metadata))need(k.equals("fields")||k.equals("migration"),"metadata wrapper");if(metadata.has("migration")){JsonObject m=metadata.getAsJsonObject("migration");JsonElement semantic=m.get("unknownSemantics");unsupported|=semantic==null||!semantic.isJsonPrimitive()||!semantic.getAsJsonPrimitive().isBoolean()||semantic.getAsBoolean();}
    }
    private void readElements()throws IOException {In in=new In(bytes(sections.get(2)));int n=in.count(NativeBlueprintLimits.CELLS+4096);byte[] last=null;
        for(int i=0;i<n;i++){int kind=in.u8();byte[] json=in.blob(NativeBlueprintLimits.RECORD_BYTES);JsonObject o=objectCanonical(json);unsupported|=!NativeBlueprintSemantics.elementKnown(o);need(kind==0||kind==1,"element kind");need((kind==1)==(o.has("block")&&o.get("block").isJsonPrimitive()&&o.getAsJsonPrimitive("block").isString()&&o.get("block").getAsString().equals("minecraft:air")),"air element");for(String k:keys(o))need(Arrays.asList("block","metadata","item","damage","count","defaultItem","defaultDamage").contains(k),"element field");byte[] e=new byte[json.length+1];e[0]=(byte)kind;System.arraycopy(json,0,e,1,json.length);need(last==null||compare(last,e)<0,"element canonical order");elements.add(e);last=e;}in.end();}
    private void readIndex()throws IOException {Section s=sections.get(3);need(s.size<=NativeBlueprintLimits.INDEX_BYTES,"geometry index budget");In in=new In(bytes(s));int x=in.i32(),y=in.i32(),z=in.i32();long sx=in.u32(),sy=in.u32(),sz=in.u32();int ax=in.i32(),ay=in.i32(),az=in.i32();long nl=in.u32(),np=in.u32();need(in.u32()==0,"index reserved");occupied=in.u64();long records=in.u64();need(occupied<=NativeBlueprintLimits.CELLS&&nl<=occupied&&np<=occupied,"index count budget");need(in.u64()==80&&in.u64()==80+32*nl&&s.size==80+32*nl+48*np,"index offsets");
        frame=new Frame(x,y,z,sx,sy,sz,ax,ay,az);frame.validate(occupied);long owned=0,sumR=0,sumN=0;int lastY=0;
        for(int i=0;i<nl;i++){int ly=in.i32();long fp=in.u32(),pc=in.u32();need(in.u32()==0,"layer reserved");long r=in.u64(),n=in.u64();need(pc>0&&pc<=np-owned&&fp==owned&&(i==0||ly>lastY)&&ly>=y&&(long)ly<y+sy&&r>0&&n>0,"layer counters");layers.add(new Layer(ly,(int)fp,(int)pc,r,n));lastY=ly;owned+=pc;need(r<=occupied-sumR&&n<=occupied-sumN,"layer total overflow");sumR+=r;sumN+=n;}need(owned==np&&sumR==records&&sumN==occupied,"layer totals");
        long off=0;for(int i=0;i<np;i++){long start=in.u64(),end=in.u64(),o=in.u64();long len=in.u32(),r=in.u32(),n=in.u32();need(in.u32()==0&&in.u64()==0,"page reserved");need(start<end&&end<=frame.plane()&&o==off&&len>0&&len<=NativeBlueprintLimits.PAGE_BYTES&&r>0&&r<=n&&n<=NativeBlueprintLimits.PAGE_CELLS&&len<=sections.get(4).size-o,"page bounds");pages.add(new Page(start,end,o,(int)len,(int)r,(int)n));off+=len;}need(off==sections.get(4).size,"geometry trailer");in.end();}
    private void readPayloads()throws IOException {need(!sections.containsKey(5)||sections.get(5).size+sections.get(3).size<=NativeBlueprintLimits.INDEX_BYTES,"combined index budget");need(sections.containsKey(5)==sections.containsKey(6),"payload section pair");if(!sections.containsKey(5))return;In in=new In(bytes(sections.get(5)));long n=in.u32();need(n>0&&n<=occupied+4096&&in.u32()==0&&in.left()==n*24,"payload index");long off=0,total=0,nodes=0,logical=0;for(Section section:sections.values())if(section.type!=6)logical+=section.expanded;byte[] prev=null;
        for(int i=0;i<n;i++){long p=in.u64(),stored=in.u32(),expanded=in.u32();int kind=in.u8(),codec=in.u8();need(in.u16()==0&&in.u32()==0&&kind==1&&(codec==0||codec==1),"payload descriptor");need(p==off&&stored>0&&stored<=NativeBlueprintLimits.NBT_BYTES&&expanded<=NativeBlueprintLimits.NBT_BYTES&&stored<=sections.get(6).size-p,"payload bounds");total+=expanded;need(total<=NativeBlueprintLimits.NBT_TOTAL&&total<=NativeBlueprintLimits.LOGICAL_BYTES-logical,"NBT cumulative budget");Payload v=new Payload(p,(int)stored,(int)expanded,codec);payloads.add(v);byte[] raw=payload(i+1);need(raw.length==expanded,"raw NBT length");nodes+=NativeBlueprintNbt.validate(raw);need(nodes<=NativeBlueprintLimits.NBT_NODES,"NBT cumulative nodes");need(prev==null||compare(prev,raw)<0,"payload canonical order");prev=raw;off+=stored;}need(off==sections.get(6).size,"payload trailer");in.end();}
    private void readExtras()throws IOException {if(!sections.containsKey(7))return;In in=new In(bytes(sections.get(7)));int n=in.count((int)occupied+4096);need(n>0,"empty extra dictionary");byte[] prev=null;for(int i=0;i<n;i++){byte[] b=in.blob(NativeBlueprintLimits.RECORD_BYTES);validateWrap(objectCanonical(b),7);need(!Arrays.equals(b,NativeBlueprintJson.encode(emptyWrap()))&&(prev==null||compare(prev,b)<0),"extras canonical dictionary");extraDictionary.add(b);JsonObject w=objectCanonical(b);if(w.has("coordinateSource")){JsonObject v=w.getAsJsonObject("coordinateSource");sourceSyntax.put(i+1,new int[]{integer(v.get("presentMask")),integer(v.get("negativeZeroMask"))});}prev=b;}
        int nd=in.count(elements.size());int last=0;for(int i=0;i<nd;i++){int e=in.count(elements.size()),v=in.count(n);need(e>last&&v>0,"extra default");defaults.put(e,v);last=e;}
        int no=in.count((int)occupied);Position previous=null;for(int i=0;i<no;i++){Position p=new Position(in.i32(),in.i32(),in.i32());int v=in.count(n);need(previous==null||previous.compareTo(p)<0,"extra override order");need(frame.contains(p.x,p.y,p.z),"extra outside frame");checkSyntax(v,p.x,p.y,p.z);overrides.put(p,v);previous=p;}in.end();}
    private void validateWrap(JsonObject wrapper,int scope)throws IOException {need(wrapper.has("fields")&&wrapper.get("fields").isJsonObject(),"fields wrapper");unsupported|=!NativeBlueprintSemantics.wrapKnown(wrapper.getAsJsonObject("fields"),scope);for(String k:keys(wrapper))need(k.equals("fields")||k.equals("nbt")||scope==7&&k.equals("coordinateSource"),"unknown wrapper key");if(wrapper.has("coordinateSource")){JsonObject syntax=wrapper.getAsJsonObject("coordinateSource");need(syntax.entrySet().size()==2&&syntax.has("presentMask")&&syntax.has("negativeZeroMask"),"coordinate syntax fields");int m=integer(syntax.get("presentMask")),z=integer(syntax.get("negativeZeroMask"));need(m>=0&&m<=7&&z>=0&&z<=7&&(z&~m)==0&&(m!=7||z!=0),"coordinate syntax masks");}if(!wrapper.has("nbt"))return;JsonArray refs=wrapper.getAsJsonArray("nbt");need(refs.size()>0,"empty NBT references");String previous=null;for(JsonElement e:refs){need(e.isJsonArray()&&e.getAsJsonArray().size()==2,"NBT reference");JsonArray a=e.getAsJsonArray();String p=a.get(0).getAsString();need((scope==7?p.matches("/(?:tileNbt|itemNbt)"):scope==8?p.matches("/structures/(?:0|[1-9][0-9]*)/(?:semanticNbt|textBoxNbt)"):p.matches("/document/(?:processingNbt|semanticNbt|quantumGroupingNbt|palette/(?:0|[1-9][0-9]*)/(?:tileNbt|itemNbt))")),"unregistered NBT pointer");need(p.startsWith("/")&&(previous==null||compare(NativeBlueprintJson.encode(new com.google.gson.JsonPrimitive(previous)),NativeBlueprintJson.encode(new com.google.gson.JsonPrimitive(p)))<0),"NBT pointer order");int id=integer(a.get(1));need(id>0&&id<=payloads.size(),"NBT reference id");JsonObject parent=pointerParent(wrapper.getAsJsonObject("fields"),p);String key=pointerKey(p);need(!parent.has(key),"NBT pointer conflict");referencedPayloads.add(id);previous=p;}}
    static String pointerKey(String pointer){return unescape(pointer.substring(pointer.lastIndexOf('/')+1));}
    static String unescape(String s){return s.replace("~1","/").replace("~0","~");}
    static JsonObject pointerParent(JsonObject fields,String pointer)throws IOException {need(!pointer.matches(".*~(?:[^01]|$).*"),"invalid pointer escape");String[] parts=pointer.substring(1).split("/",-1);JsonElement e=fields;for(int i=0;i<parts.length-1;i++){String k=unescape(parts[i]);if(e.isJsonObject()){need(e.getAsJsonObject().has(k),"NBT pointer parent");e=e.getAsJsonObject().get(k);}else if(e.isJsonArray()){need(k.matches("0|[1-9][0-9]*"),"NBT pointer array");int at;try{at=Integer.parseInt(k);}catch(NumberFormatException x){throw bad("NBT pointer array");}need(at<e.getAsJsonArray().size(),"NBT pointer index");e=e.getAsJsonArray().get(at);}else throw bad("NBT pointer parent type");}need(e.isJsonObject(),"NBT pointer object leaf");return e.getAsJsonObject();}
    static int integer(JsonElement e)throws IOException {try{need(e!=null&&e.isJsonPrimitive()&&e.getAsJsonPrimitive().isNumber()&&e.toString().matches("-?(?:0|[1-9][0-9]*)"),"integer type");return Integer.parseInt(e.toString());}catch(NumberFormatException x){throw bad("integer range");}}
    private void validateWrappers()throws IOException {for(int type:new int[]{8,9})if(sections.containsKey(type))validateWrap(objectCanonical(bytes(sections.get(type))),type);if(sections.containsKey(10)){unsupported=true;In in=new In(bytes(sections.get(10)));int n=in.count(NativeBlueprintLimits.CELLS);need(n==occupied,"legacy order count");java.util.BitSet seen=new java.util.BitSet(n);for(int i=0;i<n;i++){int p=in.count(n==0?0:n-1);need(!seen.get(p),"legacy order duplicate");seen.set(p);}in.end();}}
    private void checkSyntax(int extra,int x,int y,int z)throws IOException {int[] masks=sourceSyntax.get(extra);if(masks==null)return;int mustZero=(~masks[0]&7)|masks[1];need(((mustZero&1)==0||x==0)&&((mustZero&2)==0||y==0)&&((mustZero&4)==0||z==0),"coordinateSource contradicts geometry");}
    private static int varSize(long value){int bytes=1;while((value>>>=7)!=0)bytes++;return bytes;}
    private void validateGeometry()throws IOException {
        long[] counts=new long[elements.size()+1];
        boolean[] extraUsed=new boolean[extraDictionary.size()+1];
        Map<Integer,Map<Integer,Integer>> differences=new HashMap<>();
        java.util.Iterator<Map.Entry<Position,Integer>> it=overrides.entrySet().iterator();
        Map.Entry<Position,Integer> next=it.hasNext()?it.next():null;
        long total=0;int xMin=Integer.MAX_VALUE,xMax=Integer.MIN_VALUE,zMin=Integer.MAX_VALUE,zMax=Integer.MIN_VALUE;
        for(Layer l:layers){
            long n=0,r=0,end=-1;int oldElement=0;Page previous=null;
            for(int i=l.first;i<l.first+l.pages;i++){
                Page page=pages.get(i);need(page.start>=end,"overlapping pages");boolean first=true;
                try(RunCursor c=new RunCursor(new Layer(l.y,i,1,page.records,page.cells))){
                    while(c.next()){
                        long start=c.start(),runEnd=start+c.length();
                        int el=c.element(),def=defaults.getOrDefault(el,0);
                        if(first&&previous!=null&&previous.cells<NativeBlueprintLimits.PAGE_CELLS){
                            int wouldFit=Math.min(c.length(),NativeBlueprintLimits.PAGE_CELLS-previous.cells);
                            need(previous.bytes+varSize(start-end)+varSize(wouldFit)+varSize(el)>NativeBlueprintLimits.PAGE_BYTES,
                                "noncanonical early page boundary");
                        }
                        need(!(end==start&&oldElement==el)||first&&previous!=null&&previous.cells==NativeBlueprintLimits.PAGE_CELLS,
                            "nonmaximal geometry run");
                        first=false;oldElement=el;end=runEnd;counts[el]+=c.length();n+=c.length();r++;
                        int za=c.z(start),zb=c.z(end-1);zMin=Math.min(zMin,za);zMax=Math.max(zMax,zb);
                        xMin=Math.min(xMin,za==zb?c.x(start):frame.minX);
                        xMax=Math.max(xMax,za==zb?c.x(end-1):(int)(frame.minX+frame.sizeX-1));
                        long unchecked=start;
                        while(next!=null){
                            Position pos=next.getKey();need(pos.y>=l.y,"extra target in absent layer");
                            if(pos.y>l.y)break;long point=frame.position(pos.x,pos.z);
                            if(point>=end)break;
                            need(point>=start&&next.getValue()!=def,"invalid override target/default");
                            validateDefaultRange(def,l.y,unchecked,point,c);
                            int extra=next.getValue();extraUsed[extra]=true;
                            differences.computeIfAbsent(el,k->new HashMap<>()).merge(extra,1,Integer::sum);
                            unchecked=point+1;next=it.hasNext()?it.next():null;
                        }
                        validateDefaultRange(def,l.y,unchecked,runEnd,c);
                    }
                }
                previous=page;
            }
            need(n==l.cells&&r==l.records,"layer counters");total+=n;
        }
        need(next==null&&total==occupied,"orphan extra or wrong occupancy");
        if(occupied>0)need(xMin==frame.minX&&zMin==frame.minZ&&xMax==(long)frame.minX+frame.sizeX-1
            &&zMax==(long)frame.minZ+frame.sizeZ-1&&layers.get(0).y==frame.minY
            &&layers.get(layers.size()-1).y==(long)frame.minY+frame.sizeY-1,"geometry extrema");
        byte[] empty=NativeBlueprintJson.encode(emptyWrap());
        Map<Long,Integer> histogram=new java.util.LinkedHashMap<>();
        for(int el=1;el<counts.length;el++){
            need(counts[el]>0,"unreferenced element");int def=defaults.getOrDefault(el,0);
            Map<Integer,Integer> diff=differences.get(el);
            long base=counts[el];
            if(diff!=null)for(int value:diff.values())base-=value;
            if(base>0){extraUsed[def]=true;histogram.put(NativeBlueprintSpatialView.descriptor(el,def),(int)base); }
            if(diff!=null)for(Map.Entry<Integer,Integer> entry:diff.entrySet()){
                int id=entry.getKey();histogram.put(NativeBlueprintSpatialView.descriptor(el,id),entry.getValue());byte[] other=id==0?empty:extraDictionary.get(id-1),winner=def==0?empty:extraDictionary.get(def-1);
                need(base>entry.getValue()||base==entry.getValue()&&compare(winner,other)<0,"noncanonical extra majority");
            }
        }
        for(int i=1;i<extraUsed.length;i++)need(extraUsed[i],"unused extra dictionary entry");
        spatial=new NativeBlueprintSpatialView(this,histogram);
    }
    private void validateDefaultRange(int id,int y,long lo,long hi,RunCursor c)throws IOException {
        if(lo>=hi||!sourceSyntax.containsKey(id))return;
        // An interval's endpoints suffice unless it crosses a row: then all intermediate X values occur.
        int[] m=sourceSyntax.get(id);int zero=(~m[0]&7)|m[1];
        checkSyntax(id,c.x(lo),y,c.z(lo));checkSyntax(id,c.x(hi-1),y,c.z(hi-1));
        if((zero&1)!=0 && c.z(lo)!=c.z(hi-1))need(frame.sizeX==1,"coordinateSource crosses nonzero x");
    }
    private byte[] bytes(Section s)throws IOException{return read(file,s.offset,s.size);}
    private static JsonObject objectCanonical(byte[] bytes)throws IOException {JsonElement e=NativeBlueprintJson.canonical(bytes);need(e.isJsonObject(),"object required");return e.getAsJsonObject();}
    private static Set<String> keys(JsonObject o){Set<String> s=new HashSet<>();for(Map.Entry<String,JsonElement> e:o.entrySet())s.add(e.getKey());return s;}
    static final class Section {final int type,flags,size,codec,version;final long offset,expanded;Section(int t,int f,long o,int n,long e,int c,int v){type=t;flags=f;offset=o;size=n;expanded=e;codec=c;version=v;}}
    static final class Layer {final int y,first,pages;final long records,cells;Layer(int y,int f,int p,long r,long n){this.y=y;first=f;pages=p;records=r;cells=n;}}
    static final class Page {final long start,end,offset;final int bytes,records,cells;Page(long s,long e,long o,int b,int r,int n){start=s;end=e;offset=o;bytes=b;records=r;cells=n;}}
    static final class Payload {final long offset;final int stored,expanded,codec;Payload(long o,int s,int e,int c){offset=o;stored=s;expanded=e;codec=c;}}
    public static final class Frame {
        public final int minX,minY,minZ,anchorX,anchorY,anchorZ;public final long sizeX,sizeY,sizeZ;
        Frame(int x,int y,int z,long sx,long sy,long sz,int ax,int ay,int az){minX=x;minY=y;minZ=z;sizeX=sx;sizeY=sy;sizeZ=sz;anchorX=ax;anchorY=ay;anchorZ=az;}
        void validate(long n)throws IOException {need(sizeX==0||sizeZ<=Long.MAX_VALUE/sizeX,"plane overflow");if(n==0)need(minX==0&&minY==0&&minZ==0&&sizeX==0&&sizeY==0&&sizeZ==0,"empty frame");else need(sizeX>0&&sizeY>0&&sizeZ>0&&(long)minX+sizeX-1<=Integer.MAX_VALUE&&(long)minY+sizeY-1<=Integer.MAX_VALUE&&(long)minZ+sizeZ-1<=Integer.MAX_VALUE,"frame endpoint");}
        long plane(){return sizeX*sizeZ;}
        public boolean contains(int x,int y,int z){return x>=minX&&y>=minY&&z>=minZ&&(long)x-minX<sizeX&&(long)y-minY<sizeY&&(long)z-minZ<sizeZ;}
        public long position(int x,int z){return((long)z-minZ)*sizeX+((long)x-minX);}
    }
    static final class Position implements Comparable<Position>{final int x,y,z;Position(int x,int y,int z){this.x=x;this.y=y;this.z=z;}public int compareTo(Position o){int c=Integer.compare(y,o.y);if(c==0)c=Integer.compare(z,o.z);return c==0?Integer.compare(x,o.x):c;}public int hashCode(){return((x*31)+y)*31+z;}public boolean equals(Object o){return o instanceof Position&&compareTo((Position)o)==0;}}
    public final class RunCursor implements Closeable {
        private final Layer layer;private int nextPage,pageRecords,pageCells;private long position,start;private int length,element;private Page page;private In data;
        RunCursor(Layer l){layer=l;nextPage=l==null?0:l.first;}
        public boolean next()throws IOException {if(layer==null)return false;if(data==null||data.left()==0){if(data!=null){data.end();need(pageRecords==page.records&&pageCells==page.cells&&position==page.end,"page actual counters");}if(nextPage>=layer.first+layer.pages)return false;page=pages.get(nextPage++);data=new In(read(file,sections.get(4).offset+page.offset,page.bytes));position=page.start;pageRecords=pageCells=0;}
            long gap=data.var(63);long len=data.var(32),id=data.var(32);need((pageRecords!=0||gap==0)&&gap<=page.end-position&&len>0&&len<=page.cells-pageCells&&len<=page.end-position-gap&&id>0&&id<=elements.size(),"geometry record");start=position+gap;position=start+len;length=(int)len;element=(int)id;pageRecords++;pageCells+=length;need(pageRecords<=page.records&&pageCells<=page.cells,"page count excess");return true;}
        public long start(){return start;}public int length(){return length;}public int element(){return element;}public int y(){return layer.y;}
        public int x(long p){return(int)(frame.minX+p%frame.sizeX);}public int z(long p){return(int)(frame.minZ+p/frame.sizeX);}public void close(){}
    }
}
