package gtmb.v0111.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The same test-only fixture witness is used with 0.1.9, 0.1.10 and the candidate.
 * It admits only the runner's fixed, copied, already-owned fixture world and never replaces a product class.
 * Production import/process/Save/payment/placement/raw operations still arrive from the connected client.
 */
public final class CompositeWorldWitness {
    public static final String LIGHTING_PROFILE="vanilla-block-light-v1";
    private final Object world, actor, manifest, result;
    private final int[] source, bankOrigin;
    private final List<Object> baseline = new ArrayList<Object>(), stock = new ArrayList<Object>(), fluids = new ArrayList<Object>();
    private final List<Object> minimumPhysicalItems = new ArrayList<Object>(), minimumPhysicalFluids = new ArrayList<Object>();
    private int sourcePhysicalPatternCount;
    private Object drive, itemMonitor, fluidMonitor, actionSource, bankBefore;
    private int baselineCursor, sourceNonAirBlocks, cursor, tiles, sourceAir;
    private int[] target;
    private String checking="", funded="", saved="";
    private final List<String> mismatches = new ArrayList<String>();
    private BigInteger euBefore=BigInteger.ZERO, euQuote=BigInteger.ZERO, wirelessBefore=BigInteger.ZERO;
    private BigInteger fixtureEuCredits = new BigInteger("100000000000");
    private long operationStarted;
    private boolean awaitingFormation;
    private final MessageDigest staticSourceDigest = MessageDigest.getInstance("SHA-256");
    private static final String SOURCE_FINGERPRINT_ALGORITHM = "canonical-nbt-v1";
    private static Field nbtListElementsField;
    private int baselineScanTicks;
    private long baselineFirstWorldTick, baselineLastWorldTick;
    private CompositeEuLedger euLedger;
    private boolean lightingInstalled;
    private final long lightingStarted=System.nanoTime();
    private final List<int[]> lightingPoints=new ArrayList<int[]>();
    private final Map<String,Object> pendingAeSamples=new LinkedHashMap<String,Object>();
    private final List<int[]> quantumExpected=new ArrayList<int[]>();
    private final Map<String,Long> quantumStarted=new LinkedHashMap<String,Long>(),quantumLastTick=new LinkedHashMap<String,Long>();
    private boolean quantumEnumerated;
    private static final String QUANTUM_TILE="com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";

    public CompositeWorldWitness(Object world,Object actor,Object manifest,Object seedReceipt)throws Exception {
        this.world=world;this.actor=actor;this.manifest=manifest;this.source=ints(manifest,"source");this.bankOrigin=ints(manifest,"bank");result=nbt();
        check(Arrays.equals(ints(manifest,"size"),new int[]{40,40,40}),"immutable exact fixture dimensions");
        check((Integer)get(actor,"field_71093_bK")==integer(manifest,"dimension"),"actual connected actor dimension");
        check(!(Boolean)get(get(actor,"field_71075_bZ"),"field_75098_d"),"survival actor required");
        Object gameType=call(get(actor,"field_71134_c"),"func_73081_b");
        check((Integer)call(gameType,"func_77148_a")==0,"actual native GameType SURVIVAL(0)");
        putInt(result,"actualNativeGameType",0);
        long quantumPreloadStarted=System.nanoTime();int quantumPreloadChunks=0;
        for(int cx=source[0]>>4;cx<=(source[0]+39)>>4;cx++)for(int cz=source[2]>>4;cz<=(source[2]+39)>>4;cz++){
            Object chunk=call(get(world,"field_73059_b"),"func_73158_c",cx,cz);
            check(chunk!=null&&!Class.forName("net.minecraft.world.chunk.EmptyChunk").isInstance(chunk)&&existingChunk(cx<<4,cz<<4)==chunk,"native source fixture chunk is actually resident before quantum roster enumeration");quantumPreloadChunks++;
        }
        putInt(result,"quantumSourcePreloadChunks",quantumPreloadChunks);putLong(result,"quantumSourcePreloadNanos",System.nanoTime()-quantumPreloadStarted);
        putText(result,"quantumSourcePreloadScope","once before timing, explicit native loadChunk for only manifest source40-cube; actual resident identity verified; no ticket/lease increase");
        prepareLighting();
        Object stone=call(get(Class.forName("net.minecraft.block.Block"),"field_149771_c"),"func_82594_a","minecraft:stone");
        for(String kind:new String[]{"self","share","raw"}){
            int[] at=ints(manifest,kind);check((Boolean)call(world,"func_147437_c",at[0],at[1]+39,at[2]),"fresh fixed target safety pad");
            call(world,"func_147465_d",at[0],at[1]+39,at[2],stone,0,3);
        }
        putInt(result,"explicitFixtureSafetyStoneBlocks",3);
        Object inventory=get(actor,"field_71071_by");
        for(Object[] entry:new Object[][]{{32,"sourceTerminal"},{33,"shareTerminal"},{34,"paymentTerminal"}}) {
            Object data=compound(seedReceipt,(String)entry[1]);check(!keys(data).isEmpty(),"real previously bound fixture terminal "+entry[1]);
            Object stack=call(Class.forName("net.minecraft.item.ItemStack"),"func_77949_a",data);
            call(inventory,"func_70299_a",entry[0],stack);
        }
        Object projector=call(get(Class.forName("net.minecraft.item.Item"),"field_150901_e"),"func_82594_a","gtmb:neko_hologram_projector");
        Object projectorStack=construct("net.minecraft.item.ItemStack",projector);
        Object fixtureMaterials=construct("com.miaokatze.gtmb.hologram.HologramMaterials$Settings");
        set(fixtureMaterials,"main",false);set(fixtureMaterials,"containers",false);set(fixtureMaterials,"me",true);
        call(product("HologramMaterials"),"write",projectorStack,fixtureMaterials);
        checkOnlyMeSettings(call(product("HologramMaterials"),"read",projectorStack),"new fixture projector roundtrip");
        call(inventory,"func_70299_a",0,projectorStack);set(inventory,"field_70461_c",0);
        putText(result,"materialSourceSetup","fixture presets only-ME through normal HologramMaterials.Settings/write on the fresh held projector before product sessions, import, target lock or quotes; source-selection UI is not exercised");
        call(get(actor,"field_71069_bz"),"func_75142_b");
        Object paymentReceipt=compound(seedReceipt,"paymentReceipt");
        int[] drivePosition=ints(paymentReceipt,"physicalDrivePosition");
        check(drivePosition.length==3,"seed explicitly records the actual independent payment drive position");
        for(int axis=0;axis<3;axis++)check(drivePosition[axis]>=bankOrigin[axis]&&drivePosition[axis]<bankOrigin[axis]+32,"recorded payment drive stays inside the owned bank");
        check(compound(paymentReceipt,"terminal").equals(compound(seedReceipt,"paymentTerminal")),"seed payment receipt and carried native terminal binding agree");
        tag(result,"seedPaymentReceipt",call(paymentReceipt,"func_74737_b"));
        drive=call(world,"func_147438_o",drivePosition[0],drivePosition[1],drivePosition[2]);
        check(drive!=null&&drive.getClass().getName().equals("appeng.tile.storage.TileDrive"),"same seeded physical finite drive");
        actionSource=construct("appeng.api.networking.security.PlayerSource",actor,drive);
        Object owner=call(actor,"func_110124_au");Class<?> wireless=Class.forName("gregtech.common.misc.WirelessNetworkManager");
        call(wireless,"strongCheckOrAddUser",owner);
        check(((BigInteger)call(wireless,"getUserEU",owner)).signum()==0,"dedicated cloned fixture actor account starts empty");
        check((Boolean)call(wireless,"addEUToGlobalEnergyMap",owner,new BigInteger("100000000000")),"one finite native fixture EU credit");
        putText(result,"fixtureNativeEuCredit","100000000000");
        putText(result,"status","BASELINE");putBool(result,"actualNetty",true);putText(result,"nativeSave","NOT_RUN");
        putText(result,"fixtureScope","same closed owned seed-world copy; exact manifest; independent test-only native world/payment witness; no new product helpers installed into historical product");
        if(Boolean.getBoolean(CompositeEuLedger.PROPERTY))putText(result,"rawNativeEuLedger","NOT_OBSERVED: no CUT-before-removal observation hook; raw world PASS does not claim EU conservation");
    }

    public void tick()throws Exception {
        if(euLedger!=null)euLedger.tick();
        if(!lightingInstalled){check(System.nanoTime()-lightingStarted<30_000_000_000L,"30-second lighting setup deadline awaiting actual client before sample");return;}
        if(baselineCursor==0&&!lightingPropagated())return;
        if(baselineCursor==0&&!quantumReady("source",source))return;
        if(baselineCursor<64000){
            baselineLastWorldTick=(Long)call(world,"func_82737_E");
            if(baselineScanTicks++==0)baselineFirstWorldTick=baselineLastWorldTick;
            for(int n=0;n<256&&baselineCursor<64000;n++,baselineCursor++){
                Object row=read(source,baselineCursor);baseline.add(row);
                if(!text(row,"block").equals("minecraft:air"))sourceNonAirBlocks++;
                inspectPhysicalMinimum(row);staticSourceDigest.update(canonicalBytes(normalize(row,"raw",true)));
            }
            if(baselineCursor==64000){
                tag(result,"lightingAfter",lightingSnapshot());
                captureAeSample("source",source,"source-after-independent-baseline-before-business");
                putInt(result,"sourceVolume",baselineCursor);putInt(result,"sourceNonAirBlocks",sourceNonAirBlocks);
                putInt(result,"sourceAirBlocks",baselineCursor-sourceNonAirBlocks);putInt(result,"expectedBlueprintCells",sourceNonAirBlocks);
                putText(result,"sourceFingerprintAlgorithm",SOURCE_FINGERPRINT_ALGORITHM);
                putText(result,"blueprintCountBasis","one independent native World baseline scan of all 64000 positions; vanilla air excluded from ordinary blueprint cells; raw includes air");
                putText(result,"sourceSha256",hash(baseline));StringBuilder value=new StringBuilder();for(byte b:staticSourceDigest.digest())value.append(String.format("%02x",b&255));putText(result,"staticSourceSha256",value.toString());putText(result,"status","READY");
            }
            return;
        }
        if(!checking.isEmpty()) {
            if(awaitingFormation){if(!formedTarget()||!quantumReady(checking,target))return;captureAeSample(checking,target,checking+"-after-natural-formation-before-world-oracle");awaitingFormation=false;putText(result,"status","CHECK_WORLD_"+checking);}
            for(int n=0;n<256&&cursor<64000;n++,cursor++) {
                Object expected=normalize(baseline.get(cursor),checking,true),actual=normalize(read(target,cursor),checking,false);
                if(has(expected,"tile"))tiles++;
                if(!expected.equals(actual)&&mismatches.size()<16)mismatches.add(cursor+": expected="+abbreviate(expected)+" actual="+abbreviate(actual));
                if(checking.equals("raw")){Object sourceCell=read(source,cursor);if(text(sourceCell,"block").equals("minecraft:air")&&!has(sourceCell,"tile"))sourceAir++;}
            }
            putInt(result,"oracleComparedCells",cursor);putInt(result,"oracleTotalCells",64000);
            if(cursor==64000){String kind=checking;checking="";putInt(result,kind+"ComparedCells",cursor);putInt(result,kind+"ComparedTiles",tiles);putInt(result,kind+"SourceAirCells",sourceAir);putText(result,kind+"Mismatches",mismatches.toString());putLong(result,kind+"PostcheckNanos",System.nanoTime()-operationStarted);check(mismatches.isEmpty(),"independent "+kind+" native data mismatch: "+mismatches);if(kind.equals("raw"))check(sourceAir==64000,"raw source independently empty at every selected cell");putBool(result,kind+"Passed",true);putText(result,"status","READY");}
        }
        observeSave();
    }

    public boolean ready(){return baselineCursor==64000&&checking.isEmpty();}
    public boolean lightingInstalled(){return lightingInstalled;}

    /** Read only existing chunk TE maps; never call a lazy native getTileEntity/getNode getter. */
    private Object existingChunk(int x,int z)throws Exception {
        Object chunks=get(get(world,"field_73059_b"),"field_73244_f");
        Object chunk=call(chunks,"func_76164_a",call(Class.forName("net.minecraft.world.ChunkCoordIntPair"),"func_77272_a",x>>4,z>>4));
        return chunk==null||Class.forName("net.minecraft.world.chunk.EmptyChunk").isInstance(chunk)?null:chunk;
    }
    private Map<String,Object> existingTiles(int[] origin)throws Exception {
        Map<String,Object> found=new LinkedHashMap<String,Object>();
        for(int cx=origin[0]>>4;cx<=(origin[0]+39)>>4;cx++)for(int cz=origin[2]>>4;cz<=(origin[2]+39)>>4;cz++){
            Object chunk=existingChunk(cx<<4,cz<<4);if(chunk==null)return null;
            for(Object tile:((Map<?,?>)get(chunk,"field_150816_i")).values()){
                int x=(Integer)get(tile,"field_145851_c"),y=(Integer)get(tile,"field_145848_d"),z=(Integer)get(tile,"field_145849_e");
                if(x>=origin[0]&&x<origin[0]+40&&y>=origin[1]&&y<origin[1]+40&&z>=origin[2]&&z<origin[2]+40)found.put((x-origin[0])+","+(y-origin[1])+","+(z-origin[2]),tile);
            }
        }
        return found;
    }
    private boolean quantumReady(String kind,int[] origin)throws Exception {
        long now=System.nanoTime(),tick=(Long)call(world,"func_82737_E");
        if(!quantumStarted.containsKey(kind))quantumStarted.put(kind,now);
        Object report=compound(result,kind+"QuantumReadiness");
        check(now-quantumStarted.get(kind)<180_000_000_000L,"180-second native quantum readiness deadline "+kind+": "+report);
        if(quantumLastTick.containsKey(kind)&&tick-quantumLastTick.get(kind)<20)return false;
        quantumLastTick.put(kind,tick);long readStarted=System.nanoTime();
        Map<String,Object> tiles=existingTiles(origin);String missing="";int complete=0,seen=0;
        if(tiles==null)missing="one or more required 40-cube chunks are not real loaded chunks";
        if(!quantumEnumerated&&tiles!=null){
            int total=0,skipped=0;
            for(Map.Entry<String,Object> entry:tiles.entrySet())if(entry.getValue().getClass().getName().equals(QUANTUM_TILE)){
                total++;Object tile=entry.getValue();
                if((Integer)get(tile,"anchorDim")==Integer.MIN_VALUE){skipped++;continue;}
                quantumExpected.add(new int[]{(Integer)get(tile,"field_145851_c")-source[0],(Integer)get(tile,"field_145848_d")-source[1],(Integer)get(tile,"field_145849_e")-source[2]});
            }
            check(!quantumExpected.isEmpty(),"independent native source contains configured quantum nodes");quantumEnumerated=true;
            putText(result,"quantumEnumerationSource","original real loaded source chunkTileEntityMap values inside manifest 40-cube, before baseline; exact native QN class; configured iff native anchorDim != Integer.MIN_VALUE, irrespective of linked/connection");
            putInt(result,"quantumSourceTotal",total);putInt(result,"quantumExpectedConfigured",quantumExpected.size());putInt(result,"quantumSkippedUnconfigured",skipped);putText(result,"quantumSkippedReason","native anchorDim == Integer.MIN_VALUE only");
        }
        if(tiles!=null&&quantumEnumerated)for(int[] offset:quantumExpected){
            String position=offset[0]+","+offset[1]+","+offset[2];Object tile=tiles.get(position);
            String reason=tile==null?"expected quantum TE missing":!tile.getClass().getName().equals(QUANTUM_TILE)?"expected exact native QN class, got "+tile.getClass().getName():"";
            if(reason.isEmpty()){seen++;reason=quantumPendingReason(tile);}
            if(reason.isEmpty())complete++;else if(missing.isEmpty())missing="offset "+position+" world "+(origin[0]+offset[0])+","+(origin[1]+offset[1])+","+(origin[2]+offset[2])+": "+reason;
        }
        java.util.Set<Object> grids=Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());int pathPending=0;
        if(tiles!=null)for(int[] offset:quantumExpected){Object tile=tiles.get(offset[0]+","+offset[1]+","+offset[2]);if(tile==null||!tile.getClass().getName().equals(QUANTUM_TILE))continue;Object proxy=get(tile,"gridProxy"),node=proxy==null?null:get(proxy,"node");if(node!=null&&get(node,"myGrid")!=null)grids.add(get(node,"myGrid"));}
        for(Object grid:grids){String reason=quantumPathPending(grid);if(!reason.isEmpty()){pathPending++;if(missing.isEmpty())missing=reason;}}
        boolean ready=quantumEnumerated&&tiles!=null&&complete==quantumExpected.size()&&pathPending==0;
        putInt(report,"expectedConfigured",quantumExpected.size());putInt(report,"seenExactNativeNodes",seen);putInt(report,"completeNativeConnections",complete);putText(report,"firstMissing",missing);putBool(report,"ready",ready);
        putInt(report,"distinctActualQnGrids",grids.size());putInt(report,"pathPendingGrids",pathPending);
        putLong(report,"sampleWorldTick",tick);putLong(report,"waitNanos",now-quantumStarted.get(kind));putLong(report,"deadlineNanos",180_000_000_000L);putInt(report,"samplePeriodNaturalTicks",20);
        putLong(report,"readCalls",has(report,"readCalls")?(Long)call(report,"func_74763_f","readCalls")+1:1);
        putLong(report,"totalReadNanos",(has(report,"totalReadNanos")?(Long)call(report,"func_74763_f","totalReadNanos"):0)+System.nanoTime()-readStarted);
        putText(report,"scope","test-only pure native connection readiness before baseline or after natural formation; no native tick/checkStructure/bridge creation; not product CUT/PASTE timing or an AE identity/config oracle");tag(result,kind+"QuantumReadiness",report);
        putText(result,"status",ready?(kind.equals("source")?"BASELINE":"CHECK_WORLD_"+kind):"WAIT_NATIVE_QUANTUM_"+kind);return ready;
    }
    private String quantumPendingReason(Object tile)throws Exception {
        if((Integer)get(tile,"anchorDim")==Integer.MIN_VALUE)return "native anchor no longer configured";
        Object proxy=get(tile,"gridProxy"),core=proxy==null?null:get(proxy,"node");
        if(core==null||!(Boolean)get(proxy,"isReady"))return "native proxy/core not ready; offline="+get(tile,"offlineReason");
        Object edge=get(tile,"connection"),relay=get(tile,"bridgeRelayNode"),targetNode=get(tile,"bridgeTargetNode"),anchor=get(tile,"anchorConnection");
        if(edge==null)return "native connection absent; offline="+get(tile,"offlineReason");
        if(relay==null){
            Object a=call(edge,"a"),b=call(edge,"b");targetNode=a==core?b:b==core?a:null;
            if(!quantumEdge(edge,core,targetNode))return "direct native edge lacks exact endpoints/two-sided membership";
        }else{
            if(!quantumEdge(edge,core,relay))return "core-relay edge lacks exact endpoints/two-sided membership";
            if(!quantumEdge(anchor,relay,targetNode))return "relay-target edge lacks exact endpoints/two-sided membership";
            Object block=call(relay,"getGridBlock");
            if(!block.getClass().getName().equals("com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay")||get(block,"owner")!=tile||get(block,"node")!=relay)return "native relay owner/attachment mismatch";
            if(!get(core,"playerID").equals(get(relay,"playerID")))return "native relay owner player mismatch";
            Object location=get(block,"location");
            if(location==null||!get(location,"dimId").equals(get(tile,"anchorDim"))||!get(location,"x").equals(get(tile,"anchorX"))||!get(location,"y").equals(get(tile,"anchorY"))||!get(location,"z").equals(get(tile,"anchorZ")))return "native relay anchor location mismatch";
        }
        Object grid=get(core,"myGrid");if(grid==null||targetNode==null||grid!=get(targetNode,"myGrid")||relay!=null&&grid!=get(relay,"myGrid"))return "native core/relay/target do not share a non-null grid";
        Object targetProxy=call(targetNode,"getGridBlock");
        if(!Arrays.asList("appeng.me.helpers.AENetworkProxy","appeng.me.helpers.AENetworkProxyMultiblock").contains(targetProxy.getClass().getName()))return "native anchor target is not an AE proxy";
        Object host=get(targetProxy,"gp");if(!Class.forName("net.minecraft.tileentity.TileEntity").isInstance(host))return "native anchor target host is not a tile";
        if(get(host,"field_145850_b")==null||!get(get(get(host,"field_145850_b"),"field_73011_w"),"field_76574_g").equals(get(tile,"anchorDim"))||!get(host,"field_145851_c").equals(get(tile,"anchorX"))||!get(host,"field_145848_d").equals(get(tile,"anchorY"))||!get(host,"field_145849_e").equals(get(tile,"anchorZ")))return "native bridge target host does not match configured anchor coordinates";
        return "";
    }
    private String quantumPathPending(Object grid)throws Exception {
        if(!grid.getClass().getName().equals("appeng.me.Grid"))return "unsupported actual QN grid class "+grid.getClass().getName();
        Object path=call(grid,"getCache",Class.forName("appeng.api.networking.pathing.IPathingGrid"));
        if(path==null||!path.getClass().getName().equals("appeng.me.cache.PathGridCache"))return "actual QN grid lacks exact native PathGridCache";
        boolean recalculate=(Boolean)get(path,"recalculateControllerNextTick"),update=(Boolean)get(path,"updateNetwork"),boot=(Boolean)get(path,"booting");String controller=String.valueOf(get(path,"controllerState"));
        return recalculate||update||boot||controller.equals("CONTROLLER_CONFLICT")?"native path pending recalculateControllerNextTick="+recalculate+" updateNetwork="+update+" booting="+boot+" controllerState="+controller:"";
    }
    private static boolean quantumEdge(Object edge,Object a,Object b)throws Exception {
        if(edge==null||a==null||b==null)return false;Object x=call(edge,"a"),y=call(edge,"b");
        return ((x==a&&y==b)||(x==b&&y==a))&&quantumMember(a,edge)&&quantumMember(b,edge);
    }
    private static boolean quantumMember(Object node,Object edge)throws Exception {for(Object value:(Iterable<?>)call(node,"getConnections"))if(value==edge)return true;return false;}

    private void prepareLighting()throws Exception {
        putText(result,"lightingProfile",LIGHTING_PROFILE);
        lightingPoints.add(new int[]{bankOrigin[0]+32,bankOrigin[1]+2,bankOrigin[2]+28});
        lightingPoints.add(new int[]{bankOrigin[0]-1,bankOrigin[1]+2,bankOrigin[2]+20});
        for(String kind:new String[]{"self","share","raw"}){int[] at=ints(manifest,kind);lightingPoints.add(new int[]{at[0]-1,at[1]+41,at[2]-1});}
        for(int[] at:lightingPoints)for(int n=0;n<3;n++)outsideOracle(at[0],at[1]-n,at[2]);
        tag(result,"lightingBefore",lightingSnapshot());
        putText(result,"lightingScope","five finite vanilla glowstone blocks, each with stone and cobblestone below, outside all four 40-cubes and the 32-cube bank; native chunk load/setBlock/propagation only; no provider, gamma, night vision, held tool or oracle change");
    }

    /** Only the peer's real pre-light client sample authorizes this fixture-only setup, before timing. */
    public void installLighting()throws Exception {
        check(!lightingInstalled,"one lighting fixture installation");
        Object registry=get(Class.forName("net.minecraft.block.Block"),"field_149771_c");
        Object rows=listTag();
        for(int[] at:lightingPoints)for(int n=0;n<3;n++){
            int x=at[0],y=at[1]-n,z=at[2];outsideOracle(x,y,z);
            Object chunk=call(get(world,"field_73059_b"),"func_73158_c",x>>4,z>>4);
            check(chunk!=null&&!Class.forName("net.minecraft.world.chunk.EmptyChunk").isInstance(chunk),"lighting explicit native loadChunk returned a real chunk");
            check((Boolean)call(world,"func_147437_c",x,y,z),"new lighting/reference coordinate must be native air");
            String id=n==0?"minecraft:glowstone":n==1?"minecraft:stone":"minecraft:cobblestone";
            Object block=call(registry,"func_82594_a",id);
            check((Boolean)call(world,"func_147465_d",x,y,z,block,0,3),"native lighting/reference setBlock");
            check(call(world,"func_147439_a",x,y,z)==block&&(Integer)call(world,"func_72805_g",x,y,z)==0,"lighting/reference actual block and metadata readback after loadChunk");
            Object row=lightAt(world,x,y,z);putText(row,"installedBlock",id);append(rows,row);
        }
        tag(result,"lightingInstalledBlocks",rows);lightingInstalled=true;
    }

    private void outsideOracle(int x,int y,int z)throws Exception {
        for(String name:new String[]{"source","self","share","raw","bank"}){int[] at=ints(manifest,name);int size=name.equals("bank")?32:40;check(x<at[0]||x>=at[0]+size||y<at[1]||y>=at[1]+size||z<at[2]||z>=at[2]+size,"lighting must stay outside "+name);}
    }
    private Object lightingSnapshot()throws Exception {
        Object out=nbt(),rows=listTag();for(int[] at:lightingPoints)append(rows,lightAt(world,at[0],at[1],at[2]));tag(out,"points",rows);tag(out,"actor",actorLight(world,actor));putLong(out,"worldTick",(Long)call(world,"func_82737_E"));return out;
    }
    private boolean lightingPropagated()throws Exception {
        boolean ready=true;
        for(int[] at:lightingPoints)ready&=integer(lightAt(world,at[0],at[1],at[2]),"block")>0;
        Object current=actorLight(world,actor);ready&=integer(compound(current,"feet"),"block")>0&&integer(compound(current,"eye"),"block")>0;
        if(!ready)check(System.nanoTime()-lightingStarted<30_000_000_000L,"30-second server native lighting propagation deadline: "+lightingSnapshot());
        if(ready)putBool(result,"lightingNativePropagationReady",true);return ready;
    }
    public static Object actorLight(Object world,Object actor)throws Exception {
        int x=(int)Math.floor((Double)get(actor,"field_70165_t")),y=(int)Math.floor((Double)get(actor,"field_70163_u")),z=(int)Math.floor((Double)get(actor,"field_70161_v"));
        int eye=(int)Math.floor((Double)get(actor,"field_70163_u")+((Number)call(actor,"func_70047_e")).doubleValue());
        Object out=nbt();tag(out,"feet",lightAt(world,x,y,z));tag(out,"eye",lightAt(world,x,eye,z));
        Object provider=get(world,"field_73011_w");putBool(out,"hasNoSky",(Boolean)get(provider,"field_76576_e"));
        float[] table=(float[])get(provider,"field_76573_f");putText(out,"brightnessTable0",String.valueOf(table[0]));putText(out,"brightnessTable15",String.valueOf(table[15]));return out;
    }
    private static Object lightAt(Object world,int x,int y,int z)throws Exception {
        Object out=nbt();putInt(out,"x",x);putInt(out,"y",y);putInt(out,"z",z);
        boolean client=(Boolean)get(world,"field_72995_K");Object provider=call(world,"func_72863_F");
        Object chunks=get(provider,client?"field_73236_b":"field_73244_f");
        Object chunk=call(chunks,"func_76164_a",call(Class.forName("net.minecraft.world.ChunkCoordIntPair"),"func_77272_a",x>>4,z>>4));
        boolean valid=y>=0&&y<256&&Math.abs((long)x)<30000000&&Math.abs((long)z)<30000000;
        boolean real=valid&&chunk!=null&&!Class.forName("net.minecraft.world.chunk.EmptyChunk").isInstance(chunk);
        putBool(out,"coordinateValid",valid);putBool(out,"loaded",chunk!=null);putBool(out,"realChunkAvailable",real);
        if(!real){putInt(out,"block",-1);putInt(out,"sky",-1);putInt(out,"packed",-1);putText(out,"sampleStatus","UNAVAILABLE: no native real loaded chunk; no light/Anvil inference");return out;}
        String method=client?"func_72925_a":"func_72972_b";
        int block=(Integer)call(world,method,enumValue("net.minecraft.world.EnumSkyBlock","Block"),x,y,z);
        int sky=(Boolean)get(get(world,"field_73011_w"),"field_76576_e")?0:(Integer)call(world,method,enumValue("net.minecraft.world.EnumSkyBlock","Sky"),x,y,z);
        putInt(out,"block",block);putInt(out,"sky",sky);putInt(out,"packed",client?(Integer)call(world,"func_72802_i",x,y,z,0):(sky<<20|block<<4));
        putText(out,"packedBasis",client?"native client World.getLightBrightnessForSkyBlocks":"native server saved sky/block values packed as sky<<20|block<<4; client-only brightness getter is not available on dedicated server");return out;
    }
    private Object aeSnapshot(int[] origin,String label)throws Exception {
        try{return CompositeAeNetworkObserver.snapshot(world,origin,label);}catch(Exception failure){Object out=nbt();putText(out,"sampleLabel",label);putText(out,"status","OBSERVER_UNAVAILABLE");putText(out,"failure",failure.toString());return out;}
    }
    private void captureAeSample(String kind,int[] origin,String label)throws Exception {
        check(!pendingAeSamples.containsKey(kind)&&!has(result,aeReportKey(kind)),"one detached AE sample per fixed boundary");
        Object sample=aeSnapshot(origin,label);pendingAeSamples.put(kind,sample);
        Object reference=nbt();putText(reference,"sampleLabel",label);putText(reference,"status","PENDING_ARTIFACT");tag(result,aeReportKey(kind),reference);
    }
    private static String aeReportKey(String kind){check(Arrays.asList("source","self","share","raw").contains(kind),"fixed AE sample kind");return kind.equals("source")?"sourceAEAtBaselineComplete":kind+"AEAfterNativeFormation";}
    /** Detached observation is borrowed once by the server receipt writer; never embedded in report(). */
    public Object pendingAeSample(String kind){aeReportKey(kind);return pendingAeSamples.get(kind);}
    public void commitAeSample(String kind,String artifact,String sha256,long bytes)throws Exception {
        Object sample=pendingAeSamples.get(kind);check(sample!=null,"actual pending AE artifact");
        check(artifact.equals("composite-"+kind+"-ae-network.nbt")&&sha256.matches("[0-9a-f]{64}")&&bytes>0,"actual fixed native AE artifact receipt");
        Object reference=nbt();putText(reference,"status","ARTIFACT_COMMITTED");putText(reference,"artifact",artifact);putText(reference,"sha256",sha256);putLong(reference,"bytes",bytes);putText(reference,"sampleLabel",text(sample,"sampleLabel"));
        for(String key:new String[]{"nodes","edges","grids","unknowns"})putInt(reference,key+"Count",has(sample,key)?(Integer)call(call(sample,"func_74781_a",key),"func_74745_c"):0);
        if(has(sample,"status"))putText(reference,"observationStatus",text(sample,"status"));
        if(has(sample,"failure"))putText(reference,"observationFailure",text(sample,"failure"));
        tag(result,aeReportKey(kind),reference);pendingAeSamples.remove(kind);
    }
    public Object report()throws Exception{return call(result,"func_74737_b");}
    /** Detached original World samples, captured over the baseline scan rather than read again at shutdown. */
    public Object baselineSnapshot()throws Exception {
        check(baselineCursor==64000&&baseline.size()==64000,"complete independent baseline snapshot required");
        Object out=nbt(),rows=listTag();
        for(Object row:baseline)append(rows,call(row,"func_74737_b"));
        tag(out,"rows",rows);tag(out,"manifest",call(manifest,"func_74737_b"));
        putInt(out,"sourceVolume",baselineCursor);putInt(out,"sourceNonAirBlocks",sourceNonAirBlocks);
        putInt(out,"sourceAirBlocks",baselineCursor-sourceNonAirBlocks);putInt(out,"expectedBlueprintCells",sourceNonAirBlocks);
        putInt(out,"scanCellsPerTick",256);putInt(out,"scanTicks",baselineScanTicks);
        putLong(out,"firstWorldTick",baselineFirstWorldTick);putLong(out,"lastWorldTick",baselineLastWorldTick);
        putText(out,"rowOrder","index=xOffset+40*zOffset+1600*yOffset; source origin and size in manifest");
        putText(out,"samplingScope","64000 direct native World block/meta and TileEntity.writeToNBT rows over multiple natural ticks; original unnormalized NBT, not a simultaneous or shutdown snapshot");
        putText(out,"sourceFingerprintAlgorithm",SOURCE_FINGERPRINT_ALGORITHM);
        putText(out,"sourceSha256",text(result,"sourceSha256"));putText(out,"staticSourceSha256",text(result,"staticSourceSha256"));
        return out;
    }
    public void fundPlacement(String kind)throws Exception {
        check(funded.isEmpty(),"one finite supply outstanding");Object s=session();check((Boolean)get(s,"virtualPlacement")&&(Boolean)get(s,"virtualLocked"),"actual locked product placement");
        checkOnlyMeSettings(get(s,"materialSettings"),"actual session before funding");
        checkOnlyMeSettings(call(product("HologramMaterials"),"read",call(actor,"func_70694_bm")),"held projector before funding");
        int[] at=ints(manifest,kind);check((Integer)get(s,"x")==at[0]&&(Integer)get(s,"y")==at[1]&&(Integer)get(s,"z")==at[2],"exact canonical target");
        call(product("HologramResourcePlanner"),"refreshPlan",s);Object quote=get(s,"blueprintTotalResources");check(quote!=null,"actual server total quote");
        verifyPhysicalMinimum(quote,kind);
        Object proxy=call(drive,"getProxy"),storage=call(proxy,"getStorage");check((Boolean)call(proxy,"isActive"),"naturally active seeded finite grid");itemMonitor=call(storage,"getItemInventory");fluidMonitor=call(storage,"getFluidInventory");
        check(empty(itemMonitor)&&empty(fluidMonitor),"native finite bank begins empty");
        bankBefore=stockSnapshot();euBefore=(BigInteger)call(Class.forName("gregtech.common.misc.WirelessNetworkManager"),"getUserEU",call(actor,"func_110124_au"));wirelessBefore=wirelessStored();euQuote=(BigInteger)get(quote,"energy");
        if(Boolean.getBoolean(CompositeEuLedger.PROPERTY)){
            check(get(s,"materialContext")!=null,"normal refreshPlan established the actual material context before observation");
            euLedger=new CompositeEuLedger(world,actor,s,source,bankOrigin,at,euQuote);euLedger.begin();
            putText(result,"nativeEuLedgerProfile","opt-in native GTSWN hook; extra GTSWN-TEST dimension/listeners; no native scenario or UI kit; not comparable to historical gate=false environment");
        }
        check(((List<?>)get(quote,"essentia")).isEmpty(),"no unsupported essentia fixture supply");
        Object modulate=enumValue("appeng.api.config.Actionable","MODULATE");
        for(Object item:(List<?>)get(quote,"items")){Object offered=call(Class.forName("appeng.util.item.AEItemStack"),"create",call(item,"func_77946_l"));check(call(itemMonitor,"injectItems",offered,modulate,actionSource)==null,"finite exact item supply accepted");stock.add(call(offered,"copy"));}
        for(Object cost:(List<?>)get(quote,"fluids")){Object offered=call(Class.forName("appeng.util.item.AEFluidStack"),"create",call(get(cost,"stack"),"copy"));call(offered,"setStackSize",get(cost,"amount"));check(call(fluidMonitor,"injectItems",offered,modulate,actionSource)==null,"finite exact fluid supply accepted");fluids.add(call(offered,"copy"));}
        if(euQuote.signum()>0){check((Boolean)call(Class.forName("gregtech.common.misc.WirelessNetworkManager"),"addEUToGlobalEnergyMap",call(actor,"func_110124_au"),euQuote),"finite exact EU supply accepted");fixtureEuCredits=fixtureEuCredits.add(euQuote);}
        call(product("HologramResourcePlanner"),"refreshPlan",s);call(product("HologramSnapshots"),"send",s);
        checkOnlyMeSettings(get(s,"materialSettings"),"actual session after normal plan refresh");
        checkOnlyMeSettings(call(product("HologramMaterials"),"read",call(actor,"func_70694_bm")),"held projector after funding");
        check((Integer)get(s,"planMissing")==0,"actual finite ME bank made complete plan payable: "+get(s,"status"));
        funded=kind;tag(result,kind+"FundedStock",stockSnapshot());putText(result,kind+"QuotedEU",euQuote.toString());
    }

    private static void checkOnlyMeSettings(Object settings,String scope)throws Exception {
        check(!(Boolean)get(settings,"main")&&!(Boolean)get(settings,"containers")&&(Boolean)get(settings,"me"),"only-ME fixture material sources retained: "+scope);
    }

    public void checkPlacement(String kind)throws Exception {
        check(kind.equals(funded),"same actual funded placement");Object s=session();check((Integer)get(s,"job")==3&&(Integer)get(s,"cursor")==((List<?>)get(s,"work")).size(),"actual product job terminal");
        Object ledger=nbt(),itemRows=listTag(),fluidRows=listTag();
        for(Object quoted:stock){long supplied=(Long)call(quoted,"getStackSize"),remaining=remaining(itemMonitor,quoted);check(remaining==0,"exact independently observed native item balance exhausted");Object row=nbt();call(quoted,"writeToNBT",row);putLong(row,"supplied",supplied);putLong(row,"remaining",remaining);putLong(row,"nativeDebited",supplied-remaining);append(itemRows,row);}
        for(Object quoted:fluids){long supplied=(Long)call(quoted,"getStackSize"),remaining=remaining(fluidMonitor,quoted);check(remaining==0,"exact independently observed native fluid balance exhausted");Object row=nbt();call(quoted,"writeToNBT",row);putLong(row,"supplied",supplied);putLong(row,"remaining",remaining);putLong(row,"nativeDebited",supplied-remaining);append(fluidRows,row);}
        BigInteger euAfter=(BigInteger)call(Class.forName("gregtech.common.misc.WirelessNetworkManager"),"getUserEU",call(actor,"func_110124_au")),uptake=wirelessStored().subtract(wirelessBefore);
        if(euLedger!=null){
            try{tag(ledger,"nativeEuLedger",euLedger.finish(euQuote));}
            catch(CompositeEuLedger.LedgerFailure failure){tag(ledger,"nativeEuLedger",failure.receipt);tag(result,kind+"Paid",ledger);throw failure;}
            finally{euLedger=null;}
        }else check(uptake.signum()>=0&&euAfter.equals(euBefore.subtract(uptake)),"actual EU debit equals exact supplied quote plus independent native wireless hatch uptake");
        tag(ledger,"items",itemRows);tag(ledger,"fluids",fluidRows);tag(ledger,"beforeSupply",bankBefore);tag(ledger,"remainingStock",stockSnapshot());putText(ledger,"euBefore",euBefore.toString());putText(ledger,"euSupplied",euQuote.toString());putText(ledger,"euAfter",euAfter.toString());putText(ledger,"nativeWirelessHatchUptake",uptake.toString());tag(result,kind+"Paid",ledger);
        stock.clear();fluids.clear();funded="";startCheck(kind);
    }

    public void checkRaw()throws Exception{startCheck("raw");}
    private void startCheck(String kind)throws Exception{check(ready(),"one oracle operation");checking=kind;target=ints(manifest,kind);cursor=tiles=sourceAir=0;mismatches.clear();awaitingFormation=true;operationStarted=System.nanoTime();putInt(result,"oracleComparedCells",0);putInt(result,"oracleTotalCells",64000);putText(result,"status","WAIT_NATIVE_FORMATION_"+kind);}
    public void complete()throws Exception{check(ready(),"oracle completion");for(String kind:new String[]{"self","share","raw"})check(bool(result,kind+"Passed"),"all actual world postchecks");for(String kind:new String[]{"self","share"})check(bool(compound(result,kind+"SaveCommit"),"ownerCommit"),"actual production owner Save commit");putText(result,"status","PASS");}
    public void completeScenario(String kind)throws Exception{check(Arrays.asList("self","share","raw").contains(kind),"explicit supported scenario");check(ready()&&bool(result,kind+"Passed"),"actual independent selected scenario postcheck");if(!kind.equals("raw"))check(bool(compound(result,kind+"SaveCommit"),"ownerCommit"),"actual owner Save commit");putText(result,"scenario",kind);putText(result,"status","PASS");}
    public void close()throws Exception{
        if(euLedger!=null){euLedger.close();euLedger=null;putText(result,"nativeEuLedgerInterrupted","window stopped before settlement; no PASS claimed");}
        Class<?> wireless=Class.forName("gregtech.common.misc.WirelessNetworkManager");Object owner=call(actor,"func_110124_au");BigInteger remaining=(BigInteger)call(wireless,"getUserEU",owner);
        check(remaining.signum()>=0&&remaining.compareTo(fixtureEuCredits)<=0,"isolated fixture EU cleanup balance bounded by actual native credits");
        if(remaining.signum()>0)check((Boolean)call(wireless,"addEUToGlobalEnergyMap",owner,remaining.negate()),"native finite fixture EU remainder returned");
        putText(result,"fixtureEuCredits",fixtureEuCredits.toString());putText(result,"fixtureEuConsumed",fixtureEuCredits.subtract(remaining).toString());putText(result,"fixtureEuReturned",remaining.toString());
        putBool(result,"fixtureEuCleanup",((BigInteger)call(wireless,"getUserEU",owner)).signum()==0);
        baseline.clear();
        pendingAeSamples.clear();
    }

    private void observeSave()throws Exception {Object s=call(product("HologramWorkspace"),"find",call(actor,"func_110124_au"));if(s==null)return;if(!(Boolean)get(s,"blueprintOperationSuccess")||(Boolean)get(s,"blueprintDirty")||!String.valueOf(get(s,"blueprintSaveReceipt")).isEmpty())return;String json=String.valueOf(get(s,"blueprintSaveJsonSha256")),png=String.valueOf(get(s,"blueprintSavePreviewSha256"));if(json.isEmpty()||png.isEmpty())return;String identity=get(s,"id")+":"+json;if(identity.equals(saved))return;saved=identity;Object policy=get(s,"processingPolicy");if(policy==null)return;String kind=String.valueOf(get(policy,"tendency"));Object row=nbt();putText(row,"session",String.valueOf(get(s,"id")));putText(row,"localId",String.valueOf(get(s,"blueprintSaveLocalId")));putText(row,"jsonSha256",json);putText(row,"pngSha256",png);putBool(row,"ownerCommit",true);tag(result,kind+"SaveCommit",row);putBool(result,kind+"NativeSave",true);}
    private Object session()throws Exception{Object s=call(product("HologramWorkspace"),"find",call(actor,"func_110124_au"));check(s!=null&&get(s,"player")==actor,"actual actor production session");return s;}
    private boolean empty(Object monitor)throws Exception{for(Object stack:(Iterable<?>)call(monitor,"getStorageList"))if((Long)call(stack,"getStackSize")>0)return false;return true;}
    private boolean formedTarget()throws Exception{
        int ebf=0,lcr=0,formed=0,idle=0;
        for(Object tile:new ArrayList<Object>((List<?>)get(world,"field_147482_g"))) {
            int x=(Integer)get(tile,"field_145851_c"),y=(Integer)get(tile,"field_145848_d"),z=(Integer)get(tile,"field_145849_e");
            if(x<target[0]||x>=target[0]+40||y<target[1]||y>=target[1]+40||z<target[2]||z>=target[2]+40)continue;
            Object meta;try{meta=call(tile,"getMetaTileEntity");}catch(NoSuchMethodException ordinary){continue;}
            if(meta==null)continue;String name=meta.getClass().getSimpleName();
            if(name.equals("MTEElectricBlastFurnace"))ebf++;else if(name.equals("MTELargeChemicalReactor"))lcr++;else continue;
            if((Boolean)get(meta,"mMachine"))formed++;if((Integer)get(meta,"mProgresstime")==0&&(Integer)get(meta,"mMaxProgresstime")==0)idle++;
        }
        putInt(result,checking+"NativeEbf",ebf);putInt(result,checking+"NativeLcr",lcr);putInt(result,checking+"NativeFormed",formed);putInt(result,checking+"NativeIdle",idle);
        return ebf==50&&lcr==50&&formed==100&&idle==100;
    }
    private Object stockSnapshot()throws Exception{Object out=nbt(),items=listTag(),liquids=listTag();if(itemMonitor!=null)for(Object stack:(Iterable<?>)call(itemMonitor,"getStorageList")){if((Long)call(stack,"getStackSize")<=0)continue;Object row=nbt();call(stack,"writeToNBT",row);putLong(row,"nativeAmount",(Long)call(stack,"getStackSize"));append(items,row);}if(fluidMonitor!=null)for(Object stack:(Iterable<?>)call(fluidMonitor,"getStorageList")){if((Long)call(stack,"getStackSize")<=0)continue;Object row=nbt();call(stack,"writeToNBT",row);putLong(row,"nativeAmount",(Long)call(stack,"getStackSize"));append(liquids,row);}tag(out,"items",items);tag(out,"fluids",liquids);return out;}
    private long remaining(Object monitor,Object quoted)throws Exception{Object request=call(quoted,"copy");call(request,"setStackSize",Long.MAX_VALUE);Object remaining=call(monitor,"extractItems",request,enumValue("appeng.api.config.Actionable","SIMULATE"),actionSource);return remaining==null?0:(Long)call(remaining,"getStackSize");}
    private BigInteger wirelessStored()throws Exception{BigInteger sum=BigInteger.ZERO;for(Object tile:new ArrayList<Object>((List<?>)get(world,"field_147482_g"))){try{Object meta=call(tile,"getMetaTileEntity");if(meta!=null&&meta.getClass().getSimpleName().contains("Wireless")&&meta.getClass().getSimpleName().contains("Energy"))sum=sum.add(BigInteger.valueOf((Long)call(tile,"getStoredEU")));}catch(NoSuchMethodException notGT){}}return sum;}
    private Object read(int[] origin,int index)throws Exception{int x=origin[0]+index%40,y=origin[1]+index/1600,z=origin[2]+index/40%40;Object row=nbt(),block=call(world,"func_147439_a",x,y,z);putText(row,"block",String.valueOf(call(get(Class.forName("net.minecraft.block.Block"),"field_149771_c"),"func_148750_c",block)));putInt(row,"meta",(Integer)call(world,"func_72805_g",x,y,z));Object tile=call(world,"func_147438_o",x,y,z);if(tile!=null){putText(row,"tileClass",tile.getClass().getName());Object nativeTag=nbt();call(tile,"func_145841_b",nativeTag);tag(row,"tile",nativeTag);}return row;}
    private Object normalize(Object input,String kind,boolean expected)throws Exception {
        Object out=call(input,"func_74737_b");if(!has(out,"tile"))return out;Object tile=compound(out,"tile");
        for(String key:new String[]{"x","y","z","mTickTimer","mLastUpdate","mLastUpdateTime","mMachine","mUpdated","mUpdate","mNeedsUpdate","mStartUpCheck","mRuntime","mRunTime","mProgresstime","mMaxProgresstime","lastTime","lastTick","lastUpdate","updateCount","lastMetadata","channelCount","usedChannels"})remove(tile,key);
        // GT .205 saves this server-onPostTick counter at the native TE root; inventory/configuration are untouched.
        if(text(out,"tileClass").equals("gregtech.api.metatileentity.BaseMetaTileEntity")||text(tile,"id").equals("BaseMetaTileEntity"))remove(tile,"mTotalRunTime");
        // AE GridNode p=playerID, k=lastSecurityKey, g=world GridStorage ID. Retain all three:
        // no exact host/path network-rebuild exception has yet been established for this witness.
        if(kind.equals("share"))for(String key:new String[]{"Owner","OwnerUUID","owner","ownerUUID","mOwner","mOwnerName","mOwnerUuid","mOwnerUUID","UUID","uuid","SecurityKey","securityKey","lastOutput"})remove(tile,key);
        if(has(tile,"anchorDim")&&has(tile,"anchorX")){if(expected){putInt(tile,"anchorDim",integer(manifest,"dimension"));putInt(tile,"anchorX",bankOrigin[0]+3);putInt(tile,"anchorY",bankOrigin[1]+3);putInt(tile,"anchorZ",bankOrigin[2]+3+(kind.equals("share")?14:0));}remove(tile,"anchorGeneration");remove(tile,"anchorSchema");}
        return out;
    }
    private void inspectPhysicalMinimum(Object sourceCell)throws Exception {
        if(!has(sourceCell,"tile"))return;Object tile=compound(sourceCell,"tile");String id=text(tile,"id");
        if(id.equals("BaseMetaTileEntity")&&has(tile,"mID")) {
            Object prototypes=get(Class.forName("gregtech.api.GregTechAPI"),"METATILEENTITIES");int metaId=integer(tile,"mID");
            if(metaId<0||metaId>=Array.getLength(prototypes))return;Object prototype=Array.get(prototypes,metaId);if(prototype==null)return;
            String name=prototype.getClass().getSimpleName();
            // Actual GT .205 ME input buses serialize configured/network-shadow stacks in Inventory; they are not physical stock.
            if(name.contains("HatchInputBusME")||name.contains("HatchInputME"))return;
            Object inventory=call(tile,"func_150295_c","Inventory",10);java.util.Set<Integer> physicalPatternSlots=new HashSet<Integer>();
            for(int i=0;i<size(inventory);i++){Object row=entry(inventory,i),stack=loadStack(row);if(stack==null)continue;if(encodedPattern(stack))physicalPatternSlots.add(integer(row,"IntSlot"));addMinimumItem(stack);}
            if(name.equals("MTEHatchCraftingInputME")) {
                Object mirrors=call(tile,"func_150295_c","internalInventory",10);
                for(int i=0;i<size(mirrors);i++) {
                    Object mirror=entry(mirrors,i),body=compound(mirror,"patternSlotNBT");
                    if(!physicalPatternSlots.contains(integer(mirror,"patternSlot")))addMinimumItem(loadStack(compound(body,"pattern")));
                    inspectPhysicalBuffer(body,"inventory",false);inspectPhysicalBuffer(body,"fluidInventory",true);
                }
            }
        } else if(text(sourceCell,"tileClass").equals("appeng.tile.misc.TileInterface")) {
            // AE .1080 DualityInterface writes physical patterns/storage directly to TE root as #slot compounds.
            // config is a request template and is never an additional physical stack.
            inspectPhysicalBuffer(tile,"patterns",false);
            inspectPhysicalBuffer(tile,"storage",false);
        }
        inspectCablePatternSlots(tile);
        if(has(tile,"partContainer"))inspectCablePatternSlots(compound(tile,"partContainer"));
        if(id.equals("savedMultipart")){Object parts=call(tile,"func_150295_c","parts",10);for(int i=0;i<size(parts);i++){Object part=entry(parts,i);if(text(part,"id").equals("ae2_cablebus"))inspectCablePatternSlots(part);}}
    }
    private void inspectPhysicalBuffer(Object body,String key,boolean fluid)throws Exception {
        Object raw=call(body,"func_74781_a",key);
        if(raw!=null&&(Byte)call(raw,"func_74732_a")==10){Object slots=compound(body,key);for(String slot:keys(slots))if(slot.matches("#[0-9]+"))addMinimumItem(loadStack(compound(slots,slot)));return;}
        Object list=call(body,"func_150295_c",key,10);
        for(int i=0;i<size(list);i++) {
            Object row=entry(list,i);
            if(fluid){Object stack=call(Class.forName("net.minecraftforge.fluids.FluidStack"),"loadFluidStackFromNBT",row);if(stack!=null)addMinimumFluid(stack);}
            else addMinimumItem(loadStack(row));
        }
    }
    private void inspectCablePatternSlots(Object cable)throws Exception{for(int side=0;side<7;side++){if(!has(cable,"def:"+side)||!has(cable,"extra:"+side))continue;Object extra=compound(cable,"extra:"+side);if(has(extra,"craftingMode")&&has(extra,"pattern"))inspectPhysicalBuffer(extra,"pattern",false);}}
    private void addMinimumFluid(Object stack)throws Exception{for(Object existing:minimumPhysicalFluids)if((Boolean)call(existing,"isFluidEqual",stack)){set(existing,"amount",Math.addExact((Integer)get(existing,"amount"),(Integer)get(stack,"amount")));return;}minimumPhysicalFluids.add(call(stack,"copy"));}
    private Object loadStack(Object row)throws Exception{return has(row,"id")&&has(row,"Count")?call(Class.forName("net.minecraft.item.ItemStack"),"func_77949_a",row):null;}
    private boolean encodedPattern(Object stack)throws Exception{return stack!=null&&Class.forName("appeng.items.misc.ItemEncodedPattern").isInstance(call(stack,"func_77973_b"));}
    private void addMinimumItem(Object stack)throws Exception {
        if(stack==null||(Integer)get(stack,"field_77994_a")<=0)return;
        if(encodedPattern(stack)) {
            int amount=(Integer)get(stack,"field_77994_a");sourcePhysicalPatternCount+=amount;
            Object api=call(Class.forName("appeng.api.AEApi"),"instance"),defs=call(api,"definitions"),materials=call(defs,"materials"),blank=call(materials,"blankPattern");
            stack=call(call(blank,"maybeStack",amount),"get");
        }
        for(Object existing:minimumPhysicalItems)if(sameItem(existing,stack)){set(existing,"field_77994_a",Math.addExact((Integer)get(existing,"field_77994_a"),(Integer)get(stack,"field_77994_a")));return;}
        minimumPhysicalItems.add(call(stack,"func_77946_l"));
    }
    private boolean sameItem(Object left,Object right)throws Exception{return (Boolean)call(left,"func_77969_a",right)&&(Boolean)call(Class.forName("net.minecraft.item.ItemStack"),"func_77970_a",left,right);}
    private void verifyPhysicalMinimum(Object quote,String kind)throws Exception {
        List<?> items=(List<?>)get(quote,"items");Object ledger=listTag();
        check(sourcePhysicalPatternCount==3,"fixture must contain exactly three actual encoded patterns; GT native mirror is not a fourth pattern");
        for(Object quoted:items)check(!encodedPattern(quoted),"native encoded recipes are configuration; survival must pay blank patterns, never pre-encoded pattern items");
        Object api=call(Class.forName("appeng.api.AEApi"),"instance"),blankDefinition=call(call(call(api,"definitions"),"materials"),"blankPattern"),blank=call(call(blankDefinition,"maybeStack",1),"get");
        long quotedBlank=0;for(Object quoted:items)if(sameItem(blank,quoted))quotedBlank+=(Integer)get(quoted,"field_77994_a");check(quotedBlank==sourcePhysicalPatternCount,"independent three actual encoded patterns require exactly three blank materials; mirror/ghost data must not double-charge");
        for(Object expected:minimumPhysicalItems){long available=0;for(Object quoted:items)if(sameItem(expected,quoted))available+=(Integer)get(quoted,"field_77994_a");long required=(Integer)get(expected,"field_77994_a");check(available>=required,"independent source physical inventory missing from actual quote: "+expected+" expected>="+required+" quoted="+available);Object row=nbt();call(expected,"func_77955_b",row);putLong(row,"independentPhysicalMinimum",required);putLong(row,"actualQuoted",available);append(ledger,row);}
        for(Object expected:minimumPhysicalFluids){long required=(Integer)get(expected,"amount"),available=0;for(Object quoted:(List<?>)get(quote,"fluids"))if((Boolean)call(expected,"isFluidEqual",get(quoted,"stack")))available+=(Long)get(quoted,"amount");check(available>=required,"independent native crafting buffer fluid missing from actual quote");}
        tag(result,kind+"IndependentPhysicalMinimum",ledger);putInt(result,kind+"OriginalPhysicalPatterns",sourcePhysicalPatternCount);
        putText(result,kind+"MinimumScope","native GT Inventory excluding documented input-ME shadows; GT crafting mirror slots deduplicated and real item/fluid buffers; native AE interface physical patterns/storage excluding config templates; this is independent source evidence, not a second invocation of product resource planning");
    }
    private static int size(Object list)throws Exception{return (Integer)call(list,"func_74745_c");}
    private static Object entry(Object list,int index)throws Exception{return call(list,"func_150305_b",index);}
    private static String abbreviate(Object value){String s=String.valueOf(value);return s.length()>400?s.substring(0,400):s;}
    private static String hash(List<Object> rows)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");for(Object row:rows)md.update(canonicalBytes(row));StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format("%02x",b&255));return s.toString();}
    /** Self-delimiting typed NBT encoding; compound order is irrelevant, list order and numeric bits are exact. */
    private static byte[] canonicalBytes(Object value)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        canonical(out,value,0);out.flush();return bytes.toByteArray();
    }
    private static void canonicalString(DataOutputStream out,String value)throws Exception {
        // UTF-16 code units preserve even unpaired Java surrogates without replacement or modified-UTF limits.
        out.writeInt(value.length());for(int i=0;i<value.length();i++)out.writeChar(value.charAt(i));
    }
    private static void canonical(DataOutputStream out,Object value,int depth)throws Exception {
        check(value!=null&&depth<=512,"invalid or excessively nested canonical NBT");
        int type=((Byte)call(value,"func_74732_a")).intValue();out.writeByte(type);
        switch(type) {
            case 0: return;
            case 1: out.writeByte((Byte)call(value,"func_150290_f"));return;
            case 2: out.writeShort((Short)call(value,"func_150289_e"));return;
            case 3: out.writeInt((Integer)call(value,"func_150287_d"));return;
            case 4: out.writeLong((Long)call(value,"func_150291_c"));return;
            case 5: out.writeInt(Float.floatToRawIntBits((Float)call(value,"func_150288_h")));return;
            case 6: out.writeLong(Double.doubleToRawLongBits((Double)call(value,"func_150286_g")));return;
            case 7: {byte[] data=(byte[])call(value,"func_150292_c");out.writeInt(data.length);out.write(data);return;}
            case 8: canonicalString(out,(String)call(value,"func_150285_a_"));return;
            case 9: {
                int elementType=(Integer)call(value,"func_150303_d");
                check(elementType>=0&&elementType<=11,"unsupported canonical NBT list element type "+elementType);
                List<?> elements=nbtListElements(value);check(elements.size()==size(value),"canonical NBT list size mismatch");
                check(elements.isEmpty()||elementType!=0,"nonempty canonical NBT END list");
                out.writeByte(elementType);out.writeInt(elements.size());
                for(Object element:elements){check(((Byte)call(element,"func_74732_a")).intValue()==elementType,"canonical NBT list element type mismatch");canonical(out,element,depth+1);}return;
            }
            case 10: {
                List<String> names=new ArrayList<String>(keys(value));Collections.sort(names);out.writeInt(names.size());
                for(String name:names){canonicalString(out,name);canonical(out,call(value,"func_74781_a",name),depth+1);}return;
            }
            case 11: {int[] data=(int[])call(value,"func_150302_c");out.writeInt(data.length);for(int n:data)out.writeInt(n);return;}
            default: throw new IllegalStateException("Composite copied-world witness: unsupported canonical NBT tag type "+type);
        }
    }
    private static List<?> nbtListElements(Object value)throws Exception {
        if(nbtListElementsField==null){
            for(Field field:Class.forName("net.minecraft.nbt.NBTTagList").getDeclaredFields())if(List.class.isAssignableFrom(field.getType())){
                check(nbtListElementsField==null,"ambiguous native NBT list storage");field.setAccessible(true);nbtListElementsField=field;
            }
            check(nbtListElementsField!=null,"native NBT list storage unavailable");
        }
        return (List<?>)nbtListElementsField.get(value);
    }
    @SuppressWarnings({"unchecked","rawtypes"})private static Object enumValue(String type,String name)throws Exception{return Enum.valueOf((Class)Class.forName(type),name);}
    private static Class<?> product(String name)throws Exception{return Class.forName("com.miaokatze.gtmb.hologram."+name);}
    private static Object nbt()throws Exception{return construct("net.minecraft.nbt.NBTTagCompound");}
    private static Object listTag()throws Exception{return construct("net.minecraft.nbt.NBTTagList");}
    private static Object compound(Object tag,String key)throws Exception{return call(tag,"func_74775_l",key);}
    @SuppressWarnings("unchecked")private static java.util.Set<String> keys(Object tag)throws Exception{return (java.util.Set<String>)call(tag,"func_150296_c");}
    private static int[] ints(Object tag,String key)throws Exception{return (int[])call(tag,"func_74759_k",key);}
    private static int integer(Object tag,String key)throws Exception{return (Integer)call(tag,"func_74762_e",key);}
    private static String text(Object tag,String key)throws Exception{return (String)call(tag,"func_74779_i",key);}
    private static boolean bool(Object tag,String key)throws Exception{return (Boolean)call(tag,"func_74767_n",key);}
    private static boolean has(Object tag,String key)throws Exception{return (Boolean)call(tag,"func_74764_b",key);}
    private static void remove(Object tag,String key)throws Exception{call(tag,"func_82580_o",key);}
    private static void putText(Object tag,String key,String value)throws Exception{call(tag,"func_74778_a",key,value);}
    private static void putInt(Object tag,String key,int value)throws Exception{call(tag,"func_74768_a",key,value);}
    private static void putLong(Object tag,String key,long value)throws Exception{call(tag,"func_74772_a",key,value);}
    private static void putBool(Object tag,String key,boolean value)throws Exception{call(tag,"func_74757_a",key,value);}
    private static void tag(Object tag,String key,Object value)throws Exception{call(tag,"func_74782_a",key,value);}
    private static void append(Object tag,Object value)throws Exception{call(tag,"func_74742_a",value);}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException("Composite copied-world witness: "+message);}
    private static Field member(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Object get(Object o, String name) throws Exception { return member(o instanceof Class ? (Class<?>) o : o.getClass(), name).get(o instanceof Class ? null : o); }
    private static void set(Object o, String name, Object value) throws Exception { member(o.getClass(), name).set(o, value); }
    private static Object construct(String name, Object... args) throws Exception {
        for (Constructor<?> c : Class.forName(name).getDeclaredConstructors()) if (compatible(c.getParameterTypes(), args)) { c.setAccessible(true); return c.newInstance(args); }
        throw new NoSuchMethodException(name + " constructor " + args.length);
    }
    private static Object call(Object o, String name, Object... args) throws Exception {
        Class<?> type = o instanceof Class ? (Class<?>) o : o.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods())
            if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) { m.setAccessible(true); return m.invoke(o instanceof Class ? null : o, args); }
        throw new NoSuchMethodException(type.getName() + "." + name + " args=" + args.length);
    }
    private static boolean compatible(Class<?>[] types, Object[] values) {
        if (types.length != values.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> t = types[i];
            if (t.isPrimitive()) t = t == int.class ? Integer.class : t == long.class ? Long.class : t == float.class ? Float.class : t == double.class ? Double.class : t == boolean.class ? Boolean.class : t == char.class ? Character.class : t;
            if (!t.isInstance(values[i])) return false;
        }
        return true;
    }
}
