package gtmb.v0111.probe;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Independent test-only SHARE model. No World writer, registration, lazy grid/proxy getter or saveState is called.
 * Reuses only Raw's frozen pure graph/schema readers, never its relocation model or caller-provided mappings.
 * Source and target must be complete serialized v2 observations with their original same-tick path facts.
 * This is an observed selected-bank graph embedding, not inventory/configuration/energy conservation or a whole-world PASS.
 */
public final class CompositeAeShareCopyProof {
    private static final String RAW = "gtmb.v0111.probe.CompositeAeRawRelocation";
    private static final String QUANTUM = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";
    private static final String RELAY = "com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay";
    private static final String ACCEPTOR = "appeng.tile.networking.TileEnergyAcceptor";
    // AE1080 AENetworkProxy:131-142/359-360; GTMB fresh construction prepareActor:1082-1093.
    // No GT MTE, part, QN, relay or unreviewed class is granted a player-ID exception.
    private static final Set<String> OWNER_ROOTS = names(
        "appeng.tile.crafting.TileCraftingMonitorTile", "appeng.tile.crafting.TileCraftingStorageTile",
        "appeng.tile.crafting.TileCraftingTile", "appeng.tile.crafting.TileMolecularAssembler",
        "appeng.tile.misc.TileAdvancedInscriber", "appeng.tile.misc.TileCharger", "appeng.tile.misc.TileInscriber",
        "appeng.tile.misc.TileInterface", "appeng.tile.misc.TilePatternOptimizationMatrix",
        "appeng.tile.misc.TileQuartzGrowthAccelerator", "appeng.tile.misc.TileStorageReshuffle",
        "appeng.tile.misc.TileVibrationChamber", "appeng.tile.networking.TileDenseEnergyCell", ACCEPTOR,
        "appeng.tile.networking.TileEnergyCell", "appeng.tile.storage.TileChest", "appeng.tile.storage.TileDrive",
        "appeng.tile.storage.TileIOPort");
    private CompositeAeShareCopyProof() {}

    private static final String CONTROLLER="appeng.tile.networking.TileController";
    private static final String QT="com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal";
    private static final String[] ANCHORS={"anchorDim","anchorX","anchorY","anchorZ"};
    private static final String[] QT_ANCHORS={"QT_AnchorDim","QT_AnchorX","QT_AnchorY","QT_AnchorZ"};

    /**
     * Original, serializable facts only. Never call Registry.get/canonicalAnchor/resolveAnchor:
     * GTSWN Registry108-113/415-442 may load, allocate identities, repair maps or mark data dirty.
     */
    public static Object observeExistingBindings(Object world,Object selectionFacts)throws Exception {
        require("Server thread".equals(Thread.currentThread().getName()),"natural server thread required");
        require(intValue(selectionFacts,"dimension")==1048578,"owned dimension required");
        Object storage=field(world,"perWorldStorage");
        Object registry=((Map<?,?>)field(storage,"field_75749_b")).get("gtswn_quantum_controllers");
        require(registry!=null&&registry.getClass().getName().equals("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry"),
            "UNKNOWN registry is not already present in perWorldStorage loadedDataMap");
        require(field(registry,"world")==world&&(Integer)field(registry,"dimensionId")==1048578,"existing registry world/dimension differs");
        Object out=compound();text(out,"format","ae-existing-qt-controller-facts-v1");integer(out,"dimension",1048578);
        number(out,"sampleWorldTick",(Long)call(world,"func_82737_E"));
        text(out,"selectionCanonicalSha256",canonicalHash(selectionFacts));
        Object selections=child(selectionFacts,"quantumSelections");
        Set<?> groups=(Set<?>)call(selections,"func_150296_c");require(groups.size()==1,"only one actually selected authored group is supported");
        String group=String.valueOf(groups.iterator().next());Object selected=child(selections,group);
        Object stack=child(child(selected,"selected"),"stack"),live=child(selected,"liveSlot");validateTerminal(stack,live);
        Object qt=child(live,"tag");int[] destination=anchor(qt,QT_ANCHORS);
        String generation=string(qt,"QT_AnchorGeneration");
        Object inputs=elements(selectionFacts,"qnInputs",10).get(0),original=child(child(inputs,"sourceRow"),"tile");
        int[] source=anchor(original,ANCHORS);String sourceGeneration=string(original,"anchorGeneration");
        tag(out,"source",existingEntry(registry,world,source,sourceGeneration));
        tag(out,"selected",existingEntry(registry,world,destination,generation));text(out,"authoredGroup",group);
        text(out,"scope","Existing registry/controller/proxy/node/grid fields only. No resolver, synchronization, native getGridNode/getGrid, save or World writer.");
        return out;
    }
    private static Object existingEntry(Object registry,Object world,int[] at,String expectedGeneration)throws Exception {
        require(at[0]==1048578,"cross-dimension QT anchors unsupported");
        long position=pack(at[1],at[2],at[3]);Object generation=((Map<?,?>)field(registry,"memberGenerations")).get(position);
        require(((Set<?>)field(registry,"controllers")).contains(position)&&generation instanceof String&&generation.equals(expectedGeneration),
            "UNKNOWN coordinate/generation is not an existing registered current member");
        Object canonical=((Map<?,?>)field(registry,"canonicalAnchors")).get(generation);
        require(canonical!=null&&!((Set<?>)field(registry,"suspendedGenerations")).contains(generation),
            "UNKNOWN existing canonical anchor missing/suspended");
        long canonicalPosition=(Long)field(canonical,"position");
        require(canonicalPosition==position&&generation.equals(field(canonical,"generation")),
            "only explicit current canonical QT coordinates are supported; migration/alias/election is not performed");
        Object tile=((Map<?,?>)field(registry,"loadedTiles")).get(position);
        require(tile!=null&&tile.getClass().getName().equals(CONTROLLER)&&field(tile,"field_145850_b")==world
            &&(Integer)field(tile,"field_145851_c")==at[1]&&(Integer)field(tile,"field_145848_d")==at[2]&&(Integer)field(tile,"field_145849_e")==at[3],
            "UNKNOWN exact resident canonical native controller is absent");
        Object identity=((Map<?,?>)field(registry,"identities")).get(position);
        require(identity instanceof String&&identity.equals(call(tile,"gtswn$getControllerIdentity")),"existing controller registry identity differs");
        Object proxy=field(tile,"gridProxy"),node=proxy==null?null:field(proxy,"node"),grid=node==null?null:field(node,"myGrid");
        require(grid!=null&&grid.getClass().getName().equals("appeng.me.Grid"),"UNKNOWN existing controller grid is missing");
        Object storage=field(grid,"myStorage");require(storage!=null,"UNKNOWN native GridStorage is absent");
        Object out=compound();integer(out,"dimension",at[0]);integer(out,"x",at[1]);integer(out,"y",at[2]);integer(out,"z",at[3]);
        number(out,"packedPosition",position);number(out,"canonicalPosition",canonicalPosition);text(out,"memberGeneration",(String)generation);
        text(out,"canonicalGeneration",(String)field(canonical,"generation"));text(out,"controllerIdentity",(String)identity);
        text(out,"nativeClass",tile.getClass().getName());number(out,"g",(Long)call(storage,"getID"));
        integer(out,"p",(Integer)field(node,"playerID"));number(out,"k",(Long)field(node,"lastSecurityKey"));
        bool(out,"registered",true);bool(out,"suspended",false);return out;
    }
    // GTSWN v1.8.78 QuantumControllerRegistry:122-123: X26 | Y12 | Z26, not modern X/Z/Y.
    private static long pack(int x,int y,int z){return ((long)x&0x3ffffffL)<<38|((long)y&0xfffL)<<26|((long)z&0x3ffffffL);}
    /** Independent source-formula reference plus fixed vectors and signed/truncated axis boundaries. */
    private static void coordinateEncodingControls(){
        long[][] vectors={
            {0,1,0,67108864L},{0,0,1,1L},{1,0,0,274877906944L},
            {-1,0,0,-274877906944L},{0,-1,0,274810798080L},{0,0,-1,67108863L},
            {-1,-1,-1,-1L},{-33554432,-2048,-33554432,-9223371899382267904L},
            {33554431,2047,33554431,9223371899382267903L},{24627,83,4113,6769423784349713L}};
        for(long[] v:vectors)require(pack((int)v[0],(int)v[1],(int)v[2])==v[3],"fixed native X/Y/Z packed vector differs");
        int[] axes={Integer.MIN_VALUE,-33554432,-30000000,-1,0,1,255,2047,33554431,Integer.MAX_VALUE};
        int checked=0;
        for(int x:axes)for(int y:axes)for(int z:axes){
            // Verbatim arithmetic of the reviewed native pack method (tag v1.8.78:122-123).
            long reference=((long)(x&0x3FFFFFF)<<38)|((long)(y&0xFFF)<<26)|((long)(z&0x3FFFFFF));
            long actual=pack(x,y,z);require(actual==reference,"native Registry formula differs at signed/boundary coordinates");
            int expectedX=(x<<6)>>6,expectedY=(y<<20)>>20,expectedZ=(z<<6)>>6;
            require((int)(actual>>38)==expectedX&&(int)((actual<<26)>>52)==expectedY&&(int)((actual<<38)>>38)==expectedZ,
                "native Registry signed unpack does not round-trip truncated coordinate fields");
            checked++;
        }
        require(pack(0,1,0)!=pack(0,0,1),"distinct Y/Z axes alias");
        require(pack(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE)==0L
            &&pack(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE)==-1L,"full int minimum/maximum native masks differ");
        System.out.println("SHARE Registry X26/Y12/Z26: "+vectors.length+" fixed vectors and "+checked+" signed/boundary combinations checked");
    }
    private static void validateTerminal(Object selected,Object live)throws Exception {
        require(string(selected,"itemNativeClass").equals(QT)&&string(live,"itemNativeClass").equals(QT),
            "exact native quantum terminal required");
        require(string(selected,"item").equals(string(live,"item"))&&intValue(selected,"damage")==intValue(live,"damage")
            &&intValue(selected,"count")==1&&intValue(live,"count")>0,"actual selected/live terminal item differs");
        Object a=child(selected,"tag"),b=child(live,"tag");
        require(canonicalHash(a).equals(canonicalHash(b)),"selected snapshot/live terminal full raw tag differs; no synchronize is called");
        require(flag(b,"QT_Bound"),"actual terminal is not bound");
        typed(b,"QT_AnchorSchema",1);require((Byte)call(typed(b,"QT_AnchorSchema",1),"func_150290_f")==3,"unsupported native QT anchor schema");
        for(String key:QT_ANCHORS)typed(b,key,3);
        UUID.fromString(string(b,"QT_AnchorGeneration"));
    }
    private static int[] anchor(Object tile,String[] keys)throws Exception {int[] out=new int[4];for(int i=0;i<4;i++)out[i]=intValue(tile,keys[i]);return out;}
    private static String coordinate(int[] a){return a[0]+":"+a[1]+","+a[2]+","+a[3];}

    public static Proof prepare(Object sourceBefore,Object shareBankBefore,Object targetAfter,int[] sourceOrigin,int[] bankOrigin,
        int[] targetOrigin,Object selectionFacts,Object registryFacts,Object actorRegistryFacts,Object sourcePathFacts,
        Object bankPathFacts,Object targetPathFacts)throws Exception {
        require(sourceBefore!=null&&shareBankBefore!=null&&targetAfter!=null&&selectionFacts!=null&&registryFacts!=null
            &&actorRegistryFacts!=null&&sourcePathFacts!=null&&bankPathFacts!=null&&targetPathFacts!=null,
            "UNKNOWN missing original source/bank/target/selection/registry/path facts");
        require(sourceOrigin!=null&&bankOrigin!=null&&targetOrigin!=null&&sourceOrigin.length==3&&bankOrigin.length==3&&targetOrigin.length==3,
            "three exact fixture origins required");
        boolean disjoint=false;for(int i=0;i<3;i++)disjoint|=(long)sourceOrigin[i]+40<=targetOrigin[i]||(long)targetOrigin[i]+40<=sourceOrigin[i];
        require(disjoint,"overlapping SHARE source/target unsupported");
        Graph source=graph(sourceBefore,sourceOrigin),bankGraph=graph(shareBankBefore,bankOrigin),after=graph(targetAfter,targetOrigin);
        require(source.tick<=bankGraph.tick&&bankGraph.tick<=after.tick,"source/bank/target observation order differs");
        pathFacts(sourceBefore,source,sourceOrigin,sourcePathFacts);pathFacts(shareBankBefore,bankGraph,bankOrigin,bankPathFacts);
        pathFacts(targetAfter,after,targetOrigin,targetPathFacts);
        require(string(selectionFacts,"format").equals("ae-share-selection-facts-v1")&&intValue(selectionFacts,"dimension")==1048578,
            "UNKNOWN narrow original SHARE input schema");
        require(longValue(selectionFacts,"sampleWorldTick")==bankGraph.tick,"selected QT and bank graph are not observed at the same tick");
        Object inputContext=child(selectionFacts,"inputBindingContext");
        require(string(inputContext,"kind").equals("share")&&intValue(inputContext,"deidentificationLevel")==6
            &&flag(inputContext,"identityPose")&&flag(inputContext,"inputBindingComplete"),"UNKNOWN original locked SHARE input binding/identity pose");
        require(Arrays.equals((int[])raw("intArray",inputContext,"sourceOrigin"),sourceOrigin)
            &&Arrays.equals((int[])raw("intArray",inputContext,"targetOrigin"),targetOrigin),"original input and observed source/target origins differ");
        require(string(registryFacts,"format").equals("ae-existing-qt-controller-facts-v1")&&intValue(registryFacts,"dimension")==1048578
            &&longValue(registryFacts,"sampleWorldTick")==bankGraph.tick
            &&string(registryFacts,"selectionCanonicalSha256").equals(canonicalHash(selectionFacts)),"registry facts do not bind selected inputs/bank tick");
        int actor=actorId(actorRegistryFacts);require(intValue(actorRegistryFacts,"dimension")==1048578
            &&string(actorRegistryFacts,"observationBoundary").equals("same-tick-existing-native-registry")
            &&longValue(actorRegistryFacts,"sampleWorldTick")==after.tick,"actual existing actor registry at target graph tick required");
        Port sourceBank=bank(source),destination=entryPort(bankGraph,child(registryFacts,"selected"));
        Port originalEntry=entryPort(source,child(registryFacts,"source"));require(originalEntry.key.equals(sourceBank.key),"original registry entry is not the exact source QN anchor");
        require(sourceBank.g!=destination.g,"minimal SHARE model requires separate original source and selected bank grids");
        Map<Integer,AnchorBinding> anchorBindings=authoredInputs(source,sourceOrigin,selectionFacts,registryFacts,sourceBank,destination);
        ShareEmbedding model=shareEmbedding(source,bankGraph,sourceBank,destination);
        same(model.ports,after.ports.keySet(),"complete selected-bank SHARE ports");
        same(model.edges,after.edges,"complete selected-bank SHARE directed/boundary edges");
        same(model.groups,after.groups.keySet(),"complete selected-bank SHARE member groups");
        Object receipt=compound(),mappings=list(),patches=list(),groupRows=list();Map<Integer,List<Binding>> bindings=new TreeMap<Integer,List<Binding>>();
        Map<String,String> tileIds=new HashMap<String,String>();int changedP=0,changedG=0,persisted=0;
        for(String key:model.retained){Port old=bankGraph.ports.get(key),next=after.ports.get(model.bankMap.get(key));sameHost(old,next);
            retainedIdentity(old,next);compareLinks(old,next,model.bankMap);append(mappings,mapping(old,next,"retained-selected-bank"));}
        Set<Long> forbidden=new HashSet<Long>(source.groups.values());forbidden.addAll(bankGraph.groups.values());
        for(Map.Entry<List<String>,List<String>> e:model.groupMap.entrySet()){
            long oldG=source.groups.get(e.getKey()),newG=after.groups.get(e.getValue());boolean connected=e.getKey().contains(sourceBank.key);
            requireGroupId(connected,newG,destination.g,forbidden);
            Object row=compound();number(row,"sourceG",oldG);number(row,"expectedTargetG",newG);bool(row,"joinsActuallySelectedBank",connected);
            tag(row,"expectedMembers",strings(e.getValue()));append(groupRows,row);
        }
        Object actualChosen=child(child(selectionFacts,"quantumSelections"),string(registryFacts,"authoredGroup"));
        String chosenAnchor=coordinate(anchor(child(child(actualChosen,"liveSlot"),"tag"),QT_ANCHORS));
        for(Port old:source.ports.values())if(old.internal){
            Port next=after.ports.get(old.key);sameHost(old,next);newIdentity(old,next,actor);compareCopiedLinks(old,next,model.copyMap,chosenAnchor);
            if(old.p!=next.p)changedP++;if(old.g!=next.g)changedG++;append(mappings,mapping(old,next,"new-share-copy"));
            if(!old.persisted)continue;Binding binding=reviewed(old,next,tileIds);int index=old.rx+40*old.rz+1600*old.ry;
            if(!bindings.containsKey(index))bindings.put(index,new ArrayList<Binding>());bindings.get(index).add(binding);
            if(old.machine.equals(ACCEPTOR))continue;persisted++;
            if(old.g!=next.g)append(patches,patch(old,next,index,"g",4,old.g,next.g));
            if(old.p!=next.p)append(patches,patch(old,next,index,"p",3,old.p,next.p));
        }
        require(persisted>0&&!anchorBindings.isEmpty(),"no bound persisted SHARE ports/QNs");
        text(receipt,"algorithm","ae-share-selected-bank-embedding-v1");text(receipt,"status","SCOPED_SHARE_GRAPH_PROVED_ROW_BINDING_REQUIRED");
        bool(receipt,"authorizesPass",false);bool(receipt,"authorizesWholeWorldPass",false);bool(receipt,"energyConservationProved",false);
        text(receipt,"energyScope","zero exact native extra bits/no energy providers at observed instants only; no energy transfer/conservation or between-observation claim");
        text(receipt,"securityScope","original selected-bank g/p/k and every copied k exact; native permission/provider authorization is not inferred");
        text(receipt,"patchScope","exact source host/id/xyz/typed proxy g,p,k and original QN anchor fields must bind; only g,p and explicit selected-QT QN coordinates/generation may change");
        text(receipt,"excludedSourceScope","Original source and all nonselected pre-bank components are outside the target-associated graph; their deletion/persistence is not asserted");
        number(receipt,"sourceWorldTick",source.tick);number(receipt,"bankWorldTick",bankGraph.tick);number(receipt,"targetWorldTick",after.tick);
        integer(receipt,"sourcePorts",source.ports.size());integer(receipt,"targetPorts",after.ports.size());integer(receipt,"retainedBankPorts",model.retained.size());
        integer(receipt,"copiedInternalPorts",model.ports.size()-model.retained.size());integer(receipt,"expectedDirectedEdges",model.edges.size());
        integer(receipt,"completeTargetGroups",after.groups.size());integer(receipt,"reviewedPersistedPorts",persisted);integer(receipt,"newRuntimePChanges",changedP);
        integer(receipt,"newRuntimeGChanges",changedG);integer(receipt,"boundQuantumRows",anchorBindings.size());
        tag(receipt,"portMappings",mappings);tag(receipt,"groupMappings",groupRows);tag(receipt,"finiteIdentityPatchCandidates",patches);
        Object anchors=list();for(AnchorBinding b:anchorBindings.values())append(anchors,b.receipt());tag(receipt,"finiteQuantumAnchorPatches",anchors);
        tag(receipt,"selectionFacts",copy(selectionFacts));tag(receipt,"registryFacts",copy(registryFacts));tag(receipt,"actorRegistryFacts",copy(actorRegistryFacts));
        text(receipt,"nativeSourceReference","GTSWN v1.8.78 commit 2a2b3c13efad8212411af5593939f711a2cad9ec; Registry59-76/100-113/404-445 and QN301-338/406-415/1563-1577/1812-1842; actual existing native fields are type/class checked");
        tag(receipt,"sourcePathFacts",copy(sourcePathFacts));tag(receipt,"bankPathFacts",copy(bankPathFacts));tag(receipt,"targetPathFacts",copy(targetPathFacts));
        return new Proof(bindings,anchorBindings,receipt,sourceOrigin);
    }
    private static Port entryPort(Graph graph,Object entry)throws Exception {
        require(flag(entry,"registered")&&!flag(entry,"suspended")&&string(entry,"nativeClass").equals(CONTROLLER),"UNKNOWN unsupported controller registry entry");
        int[] at={intValue(entry,"dimension"),intValue(entry,"x"),intValue(entry,"y"),intValue(entry,"z")};
        require(longValue(entry,"packedPosition")==pack(at[1],at[2],at[3])&&longValue(entry,"canonicalPosition")==pack(at[1],at[2],at[3])
            &&string(entry,"memberGeneration").equals(string(entry,"canonicalGeneration")),"canonical native entry differs");UUID.fromString(string(entry,"memberGeneration"));UUID.fromString(string(entry,"controllerIdentity"));
        Port out=null;for(Port p:graph.ports.values())if(p.machine.equals(CONTROLLER)&&p.path.equals("proxy")&&p.dim==at[0]&&p.x==at[1]&&p.y==at[2]&&p.z==at[3]){
            require(out==null,"ambiguous selected controller port");out=p;}
        require(out!=null&&out.g==longValue(entry,"g")&&out.p==intValue(entry,"p")&&out.k==longValue(entry,"k"),"existing registered controller is not the exact observed graph port");
        return out;
    }
    private static Map<Integer,AnchorBinding> authoredInputs(Graph source,int[] origin,Object facts,Object registry,Port sourceBank,Port destination)throws Exception {
        Object selections=child(facts,"quantumSelections");Set<?> names=(Set<?>)call(selections,"func_150296_c");
        require(names.size()==1,"only one actually selected authored group is supported");String group=String.valueOf(names.iterator().next());
        require(group.matches("q[1-9][0-9]{0,5}")&&group.equals(string(registry,"authoredGroup")),"authored group and actual selected QT differ");
        Object chosen=child(selections,group);require(intValue(chosen,"slot")==intValue(child(chosen,"selected"),"slot"),"actual selected inventory slot differs");
        Object selected=child(child(chosen,"selected"),"stack"),live=child(chosen,"liveSlot");validateTerminal(selected,live);
        int[] targetAnchor=anchor(child(live,"tag"),QT_ANCHORS);String targetGeneration=string(child(live,"tag"),"QT_AnchorGeneration");
        require(coordinate(targetAnchor).equals(destination.dim+":"+destination.x+","+destination.y+","+destination.z)
            &&targetGeneration.equals(string(child(registry,"selected"),"canonicalGeneration")),"terminal anchor does not name the actual canonical bank component");
        Map<Integer,Port> qns=new TreeMap<Integer,Port>();for(Port p:source.ports.values())if(p.internal&&p.machine.equals(QUANTUM)&&p.path.equals("proxy")){
            require(p.links!=null&&p.links.anchor.equals(sourceBank.dim+":"+sourceBank.x+","+sourceBank.y+","+sourceBank.z),"unreviewed original QN anchor");
            require(qns.put(p.rx+40*p.rz+1600*p.ry,p)==null,"duplicate source QN root");}
        Map<Integer,AnchorBinding> out=new TreeMap<Integer,AnchorBinding>();
        for(Object input:elements(facts,"qnInputs",10)){
            int index=intValue(input,"index");Port qn=qns.get(index);require(qn!=null&&!out.containsKey(index),"unexpected/duplicate authored QN source index");
            Object row=child(input,"sourceRow"),tile=child(row,"tile"),authored=child(input,"authoredRow"),authoredTile=child(authored,"tile");
            require(string(row,"tileClass").equals(QUANTUM)&&string(tile,"id").equals((String)raw("registeredTileId",QUANTUM))
                &&intValue(tile,"x")==origin[0]+qn.rx&&intValue(tile,"y")==origin[1]+qn.ry&&intValue(tile,"z")==origin[2]+qn.rz,
                "original QN native class/id/xyz differs");
            require(string(row,"block").equals(string(authored,"block"))&&intValue(row,"meta")==intValue(authored,"meta")
                &&string(authoredTile,"id").equals(string(tile,"id")),"authored QN registry block/meta/id differs");
            raw("nodeIdentity",raw("path",tile,qn.path),qn.g,qn.p,qn.k,"SHARE original authored QN identity "+index);
            String encoded=string(input,"rawAuthoredTileNbt");require(!encoded.isEmpty()&&encoded.length()<=((1024*1024+2)/3)*4,"raw authored QN encoding missing/too large");
            byte[] bytes=java.util.Base64.getDecoder().decode(encoded);require(bytes.length<=1024*1024,"raw authored compressed NBT too large");
            Object tracker=Class.forName("net.minecraft.nbt.NBTSizeTracker").getConstructor(long.class).newInstance(16L*1024*1024);
            Object decoded=call(Class.forName("net.minecraft.nbt.CompressedStreamTools"),"func_152457_a",bytes,tracker);
            require(canonicalHash(decoded).equals(canonicalHash(authoredTile)),"authored raw QN encoding and frozen decoded input differ");
            require(string(authoredTile,"gtmbNetworkGroup").equals(group)&&string(authoredTile,"gtmbNetworkRole").equals("node"),"authored QN group/role not completely bound to actual selected terminal");
            int[] oldAnchor=anchor(tile,ANCHORS);String oldGeneration=string(tile,"anchorGeneration");
            require(coordinate(oldAnchor).equals(qn.links.anchor)&&oldGeneration.equals(string(child(registry,"source"),"canonicalGeneration")),
                "original QN anchor/generation differs from observed source/canonical registry");
            out.put(index,new AnchorBinding(index,oldAnchor,oldGeneration,targetAnchor,targetGeneration));
        }
        require(intValue(facts,"quantumRowCount")==out.size(),"narrow QN input count differs");
        require(!qns.isEmpty()&&qns.keySet().equals(out.keySet()),"missing authored QN rows; unknown is not empty");return out;
    }
    private static void requireGroupId(boolean connected,long actual,long selected,Set<Long> forbidden){require(connected?actual==selected:!forbidden.contains(actual),"copied group ID is not selected-bank g or a fresh isolated group");}
    private static void compareCopiedLinks(Port old,Port next,Map<String,String> map,String anchor){
        if(old.links==null){require(next.links==null,"unexpected copied native link");return;}
        require(map.containsKey(old.links.first)&&map.containsKey(old.links.second)&&next.links!=null&&anchor.equals(next.links.anchor)
            &&map.get(old.links.first).equals(next.links.first)&&map.get(old.links.second).equals(next.links.second),"copied QN/relay/selected-anchor evidence differs");
    }
    private static ShareEmbedding shareEmbedding(Graph source,Graph bankGraph,Port oldBank,Port chosenBank){
        ShareEmbedding out=new ShareEmbedding();List<String> chosen=null;for(List<String> members:bankGraph.groups.keySet())if(members.contains(chosenBank.key))chosen=members;
        require(chosen!=null,"actually selected bank component absent");
        for(String key:chosen){Port p=bankGraph.ports.get(key);out.retained.add(key);out.bankMap.put(key,p.absolute);out.ports.add(p.absolute);}
        for(Port p:source.ports.values())if(p.internal){require(!out.ports.contains(p.absolute),"selected pre-bank component already includes source internal ports");out.copyMap.put(p.key,p.key);require(out.ports.add(p.key),"copied relative port collision");}
        out.copyMap.put(oldBank.key,chosenBank.absolute);
        for(Edge edge:bankGraph.edges)if(out.retained.contains(edge.a)&&out.retained.contains(edge.b))out.edges.add(edge.map(out.bankMap));
        for(Edge edge:source.edges)if(source.ports.get(edge.a).internal||source.ports.get(edge.b).internal){
            boolean a=source.ports.get(edge.a).internal,b=source.ports.get(edge.b).internal;
            if(a!=b){Port outside=source.ports.get(a?edge.b:edge.a),inside=source.ports.get(a?edge.a:edge.b);
                require(outside.key.equals(oldBank.key)&&inside.machine.equals(RELAY)&&inside.links!=null&&inside.links.second.equals(oldBank.key),
                    "UNKNOWN source crossing is not the exact native relay-to-original-anchor edge");}
            out.edges.add(edge.map(out.copyMap));
        }
        for(List<String> members:source.groups.keySet()){
            Set<String> expected=new TreeSet<String>();boolean connected=members.contains(oldBank.key);
            if(connected)for(String key:chosen)expected.add(out.bankMap.get(key));
            for(String key:members)if(source.ports.get(key).internal)expected.add(key);else require(connected,"unreviewed external source component");
            require(!expected.isEmpty(),"source group has no copied internal member");List<String> fixed=Collections.unmodifiableList(new ArrayList<String>(expected));
            require(out.groups.add(fixed),"projected SHARE groups merge unexpectedly");out.groupMap.put(members,fixed);
        }return out;
    }

    public static final class Proof {
        private final Map<Integer, List<Binding>> bindings;
        private final Object evidence;
        private final Map<Integer,AnchorBinding> anchors;
        private final int[] source;
        private Proof(Map<Integer, List<Binding>> input, Map<Integer,AnchorBinding> anchors, Object receipt, int[] source) {
            this.anchors=Collections.unmodifiableMap(new TreeMap<Integer,AnchorBinding>(anchors));
            Map<Integer, List<Binding>> fixed = new TreeMap<Integer, List<Binding>>();
            for (Map.Entry<Integer, List<Binding>> e : input.entrySet()) fixed.put(e.getKey(), Collections.unmodifiableList(new ArrayList<Binding>(e.getValue())));
            this.bindings = Collections.unmodifiableMap(fixed); this.evidence = receipt; this.source = source.clone();
        }
        /** Returns a NEW copy. Never mutates the supplied baseline, an actual row, a graph, or an original source port. */
        public Object patchExpectedRow(Object originalExpectedRow, int index) throws Exception {
            require(index >= 0 && index < 64000, "source index outside exact fixture");
            Object out = copy(originalExpectedRow); List<Binding> rows = bindings.get(index);
            if (rows == null) return out;
            List<Object> nodes = validateOriginalRow(out, index, rows);
            if(anchors.containsKey(index))anchors.get(index).validate(child(out,"tile"));
            // Every original binding is checked first. Only finite g/p and selected-QT root anchor fields are written.
            for (int i = 0; i < rows.size(); i++) if (nodes.get(i) != null) {
                Binding b = rows.get(i);
                if (b.source.g != b.target.g) number(nodes.get(i), "g", b.target.g);
                if (b.source.p != b.target.p) integer(nodes.get(i), "p", b.target.p);
            }
            if(anchors.containsKey(index))anchors.get(index).patch(child(out,"tile"));
            return out;
        }
        private List<Object> validateOriginalRow(Object row, int index, List<Binding> rows) throws Exception {
            Object tile = child(row, "tile"); List<Object> nodes = new ArrayList<Object>();
            for (Binding b : rows) {
                Port old = b.source;
                require(string(row, "tileClass").equals(old.host) && string(tile, "id").equals(b.tileId), "original native source host/id differs at " + index);
                require(intValue(tile, "x") == source[0] + index % 40 && intValue(tile, "y") == source[1] + index / 1600
                    && intValue(tile, "z") == source[2] + index / 40 % 40, "original source xyz differs; call before relocation");
                if (old.metaId >= 0) require(intValue(tile, "mID") == old.metaId, "original native mID differs");
                if (old.machine.equals(ACCEPTOR)) {
                    require(!has(tile, "proxy"), "exact EnergyAcceptor native writer omits proxy; unexpected data is not authorized");
                    typed(tile, "internalCurrentPower", 6); nodes.add(null); continue;
                }
                if (old.side >= 0) raw("partDefinition", tile, b.container, old.side, old.item, old.damage);
                Object node = raw("path", tile, old.path);
                raw("nodeIdentity", node, old.g, old.p, old.k, "SHARE original source " + index + "/" + old.path);
                nodes.add(node);
            }
            return nodes;
        }
        public Object receipt() throws Exception { return copy(evidence); }
    }

    private static Port bank(Graph source) throws Exception {
        String anchor = null;
        for (Port p : source.ports.values()) if (p.internal && p.machine.equals(QUANTUM) && p.links != null) {
            String next = p.links.anchor;
            require(anchor == null || anchor.equals(next), "this SHARE fixture model supports one exact original bank anchor only"); anchor = next;
        }
        require(anchor != null, "configured original quantum-node roster missing"); Port result = null;
        for (Port p : source.ports.values()) if (p.machine.equals("appeng.tile.networking.TileController") && !p.internal
            && p.path.equals("proxy") && (p.dim + ":" + p.x + "," + p.y + "," + p.z).equals(anchor)) {
            require(result == null, "ambiguous original native bank controller"); result = p;
        }
        require(result != null, "original configured native bank controller is absent"); return result;
    }

    private static void retainedIdentity(Port old, Port next) {
        require(next != null && old.g == next.g && old.p == next.p && old.k == next.k, "original selected-bank g/p/k changed at " + old.key);
    }
    private static void newIdentity(Port old, Port next, int actor) {
        require(next != null && old.k == next.k, "copied security key changed at " + old.key);
        if (old.p != next.p) require(ownerRoot(old) && old.p == -1 && next.p == actor, "unreviewed copied player identity change at " + old.key);
    }
    private static boolean ownerRoot(Port p) { return OWNER_ROOTS.contains(p.machine) && p.host.equals(p.machine)
        && p.path.equals("proxy") && p.side == -1 && p.metaId == -1 && p.part.isEmpty(); }
    private static void sameHost(Port old, Port next) {
        require(next != null && old.host.equals(next.host) && old.machine.equals(next.machine) && old.proxy.equals(next.proxy)
            && old.path.equals(next.path) && old.metaId == next.metaId && old.side == next.side && old.part.equals(next.part)
            && old.item.equals(next.item) && old.damage == next.damage && old.persisted == next.persisted, "native host/part/path role differs at " + old.key);
    }
    private static void compareLinks(Port old, Port next, Map<String, String> map) {
        if (old.links == null) { require(next.links == null, "unexpected quantum/relay link evidence"); return; }
        require(map.containsKey(old.links.first) && map.containsKey(old.links.second), "UNKNOWN unsupported quantum owner/relay target projection");
        require(next.links != null && old.links.anchor.equals(next.links.anchor)
            && map.get(old.links.first).equals(next.links.first) && map.get(old.links.second).equals(next.links.second), "native owner/relay/anchor mapping differs at " + old.key);
    }

    private static Binding reviewed(Port old, Port next, Map<String, String> tileIds) throws Exception {
        require(old.proxy.equals("appeng.me.helpers.AENetworkProxy") || old.proxy.equals("appeng.me.helpers.AENetworkProxyMultiblock"), "unreviewed persisted proxy class");
        String container = "";
        if (rawSet("GT").contains(old.machine)) {
            require(old.host.equals("gregtech.api.metatileentity.BaseMetaTileEntity") && old.path.equals("proxy") && old.metaId >= 0 && old.side == -1 && old.part.isEmpty(), "unreviewed GT source host/path");
            Object prototypes = field(Class.forName("gregtech.api.GregTechAPI"), "METATILEENTITIES");
            require(old.metaId < Array.getLength(prototypes) && Array.get(prototypes, old.metaId) != null
                && Array.get(prototypes, old.metaId).getClass().getName().equals(old.machine), "native GT mID/prototype differs");
        } else if (rawSet("PARTS").contains(old.machine)) {
            require(old.part.equals(old.machine) && old.side >= 0 && old.side <= 6, "unreviewed source part identity");
            if (rawSet("CABLE").contains(old.host)) container = "";
            else { require(old.host.equals(QUANTUM), "unreviewed part host"); container = "partContainer"; }
            String prefix = (container.isEmpty() ? "" : container + "/") + "extra:" + old.side + "/";
            require(old.path.equals(prefix + "part") || (old.path.equals(prefix + "outer") && rawSet("OUTER").contains(old.machine)), "unreviewed part proxy path");
        } else require(rawSet("TILES").contains(old.host) && old.machine.equals(old.host) && old.path.equals("proxy")
            && old.metaId == -1 && old.side == -1 && old.part.isEmpty(), "unreviewed native tile source path");
        if (!tileIds.containsKey(old.host)) tileIds.put(old.host, (String) raw("registeredTileId", old.host));
        return new Binding(old, next, tileIds.get(old.host), container);
    }

    private static Graph graph(Object snapshot, int[] origin) throws Exception {
        Object checked = raw("graph", snapshot, origin); Graph out = new Graph(longValue(snapshot, "worldTick"));
        for (Object value : ((Map<?, ?>) field(checked, "nodes")).values()) {
            Port p = new Port(value); out.ports.put(p.key, p);
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) field(checked, "groups")).entrySet()) {
            List<String> members = new ArrayList<String>(); for (Object key : (List<?>) e.getKey()) members.add((String) key);
            long g=(Long)field(e.getValue(),"g"),extra=(Long)field(e.getValue(),"extra");
            require(extra==0L,"nonzero or noncanonical raw extra bits are outside this SHARE proof energy scope");
            out.groups.put(Collections.unmodifiableList(members),g);
        }
        for (Object row : elements(snapshot, "edges", 10)) out.edges.add(new Edge(string(row,"a"),string(row,"b"),intValue(row,"directionA"),intValue(row,"directionB"),flag(row,"hasDirection")));
        return out;
    }

    private static void pathFacts(Object snapshot, Graph graph, int[] origin, Object facts) throws Exception {
        require(string(facts,"format").equals("ae-path-facts-v1") && string(facts,"snapshotHashAlgorithm").equals("canonical-nbt-v1"), "UNKNOWN original native path-facts schema");
        require(string(facts,"snapshotCanonicalSha256").equals(canonicalHash(snapshot)), "path facts do not bind the complete original snapshot");
        require(longValue(facts,"sampleWorldTick")==graph.tick && longValue(facts,"snapshotWorldTick")==graph.tick
            && intValue(facts,"dimension")==1048578 && Arrays.equals((int[])raw("intArray",facts,"origin"),origin), "path facts tick/dimension/origin differs");
        Set<Long> expected=new HashSet<Long>(graph.groups.values()), observed=new HashSet<Long>();
        Map<Long,String> states=new HashMap<Long,String>();
        for(Object row:elements(snapshot,"grids",10))states.put(longValue(row,"g"),string(row,"controllerState"));
        for(Object row:elements(facts,"groups",10)){
            long g=longValue(row,"g");require(observed.add(g),"duplicate native path-facts grid");
            require(string(row,"gridClass").equals("appeng.me.Grid") && string(row,"pathClass").equals("appeng.me.cache.PathGridCache"),"UNKNOWN native path cache class");
            String state=string(row,"controllerState");require((state.equals("NO_CONTROLLER")||state.equals("CONTROLLER_ONLINE"))&&state.equals(states.get(g)),"UNKNOWN/different native controller state");
            require(!flag(row,"recalculateControllerNextTick") && !flag(row,"updateNetwork") && !flag(row,"booting"),"native path cache has pending work/conflict");
            if(has(row,"ready"))require(flag(row,"ready"),"contradictory native path ready field");
            if(has(row,"pendingReason"))require(string(row,"pendingReason").isEmpty(),"contradictory pending reason");
        }
        same(expected,observed,"complete same-tick path-facts g scope");
        require(intValue(facts,"expectedGroups")==expected.size() && intValue(facts,"observedNativeGroups")==observed.size(),"path-facts counts differ");
    }

    private static int actorId(Object facts) throws Exception {
        require(string(facts,"format").equals("ae-existing-actor-registry-v1"),"UNKNOWN raw actor registry schema");
        UUID actor=UUID.fromString(string(facts,"actorUUID"));require(actor.toString().equals(string(facts,"profileUUID")),"actor/profile UUID differs");
        Map<String,Integer> forward=new TreeMap<String,Integer>();Map<Integer,String> reverse=new TreeMap<Integer,String>();
        for(Object row:elements(facts,"forwardMappings",10)){
            String uuid=UUID.fromString(string(row,"uuid")).toString();require(string(row,"propertyType").equals("INTEGER")&&!flag(row,"propertyIsList"),"UNKNOWN native player property type");
            int p=Integer.parseInt(string(row,"propertyValue"));require(p>=0&&!forward.containsKey(uuid),"ambiguous native forward player mapping");forward.put(uuid,p);
        }
        for(Object row:elements(facts,"reverseMappings",10)){
            int p=intValue(row,"p");String uuid=UUID.fromString(string(row,"uuid")).toString();require(p>=0&&!reverse.containsKey(p),"ambiguous native reverse player mapping");reverse.put(p,uuid);
        }
        require(!forward.isEmpty()&&forward.size()==reverse.size(),"UNKNOWN/incomplete raw player registry scope");
        for(Map.Entry<String,Integer> e:forward.entrySet())require(e.getKey().equals(reverse.get(e.getValue())),"native forward/reverse player mapping differs");
        require(forward.containsKey(actor.toString()),"actor has no existing native player ID");return forward.get(actor.toString());
    }

    private static Object mapping(Port old,Port next,String role)throws Exception {Object row=compound();text(row,"role",role);text(row,"sourcePort",old.key);text(row,"targetPort",next.key);number(row,"oldG",old.g);number(row,"newG",next.g);integer(row,"oldP",old.p);integer(row,"newP",next.p);number(row,"k",old.k);return row;}
    private static Object patch(Port old,Port next,int index,String field,int type,long from,long to)throws Exception {Object row=compound();integer(row,"index",index);text(row,"sourcePort",old.key);text(row,"targetPort",next.key);text(row,"path","/tile/"+old.path+"/"+field);integer(row,"tagId",type);if(type==3){integer(row,"before",(int)from);integer(row,"after",(int)to);}else{number(row,"before",from);number(row,"after",to);}text(row,"status","REQUIRES_EXACT_ORIGINAL_SOURCE_ROW_BINDING");return row;}
    private static final class AnchorBinding {
        final int index;final int[] oldAt,nextAt;final String oldGeneration,nextGeneration;
        AnchorBinding(int index,int[] oldAt,String oldGeneration,int[] nextAt,String nextGeneration){this.index=index;this.oldAt=oldAt.clone();this.nextAt=nextAt.clone();this.oldGeneration=oldGeneration;this.nextGeneration=nextGeneration;}
        void validate(Object tile)throws Exception {for(int i=0;i<4;i++)require(intValue(tile,ANCHORS[i])==oldAt[i],"original typed QN anchor changed before patch at "+index+"/"+ANCHORS[i]);require(string(tile,"anchorGeneration").equals(oldGeneration),"original typed QN generation changed before patch");}
        void patch(Object tile)throws Exception {for(int i=0;i<4;i++)integer(tile,ANCHORS[i],nextAt[i]);text(tile,"anchorGeneration",nextGeneration);}
        Object receipt()throws Exception {Object out=compound();integer(out,"index",index);for(int i=0;i<4;i++){Object r=compound();text(r,"path","/tile/"+ANCHORS[i]);integer(r,"tagId",3);integer(r,"before",oldAt[i]);integer(r,"after",nextAt[i]);tag(out,ANCHORS[i],r);}Object r=compound();text(r,"path","/tile/anchorGeneration");integer(r,"tagId",8);text(r,"before",oldGeneration);text(r,"after",nextGeneration);tag(out,"anchorGeneration",r);return out;}
    }
    private static final class ShareEmbedding {final Set<String> retained=new TreeSet<String>(),ports=new TreeSet<String>();final Map<String,String> bankMap=new HashMap<String,String>(),copyMap=new HashMap<String,String>();final Set<Edge> edges=new HashSet<Edge>();final Set<List<String>> groups=new HashSet<List<String>>();final Map<List<String>,List<String>> groupMap=new HashMap<List<String>,List<String>>();}
    private static final class Binding {final Port source,target;final String tileId,container;Binding(Port a,Port b,String id,String container){source=a;target=b;tileId=id;this.container=container;}}
    private static final class Links {final String first,second,anchor;Links(String a,String b,String c){first=a;second=b;anchor=c;}}
    private static final class Port {
        final String key,absolute,host,machine,proxy,path,part,item;final int dim,x,y,z,rx,ry,rz,metaId,side,damage,p;final long g,k;final boolean internal,persisted;final Links links;
        Port(Object node)throws Exception {
            key=(String)field(node,"key");host=(String)field(node,"host");machine=(String)field(node,"machine");proxy=(String)field(node,"proxy");path=(String)field(node,"path");part=(String)field(node,"part");item=(String)field(node,"item");
            dim=(Integer)field(node,"dim");x=(Integer)field(node,"x");y=(Integer)field(node,"y");z=(Integer)field(node,"z");rx=(Integer)field(node,"rx");ry=(Integer)field(node,"ry");rz=(Integer)field(node,"rz");metaId=(Integer)field(node,"metaId");side=(Integer)field(node,"side");damage=(Integer)field(node,"damage");p=(Integer)field(node,"p");g=(Long)field(node,"g");k=(Long)field(node,"k");internal=(Boolean)field(node,"internal");persisted=(Boolean)field(node,"persisted");
            absolute="absolute:"+dim+":"+x+","+y+","+z+key.substring(key.indexOf('|'));
            Object row=field(node,"row");
            if(machine.equals(QUANTUM)){Object q=child(row,"quantumBridge");require(flag(q,"hasAnchor"),"unconfigured QN is outside this fixture SHARE model");links=new Links(string(q,"relayPort"),string(q,"targetPort"),intValue(q,"anchorDimension")+":"+intValue(q,"anchorX")+","+intValue(q,"anchorY")+","+intValue(q,"anchorZ"));}
            else if(machine.equals(RELAY))links=new Links(string(row,"relayOwnerPort"),string(row,"relayTargetPort"),intValue(row,"relayAnchorDimension")+":"+intValue(row,"relayAnchorX")+","+intValue(row,"relayAnchorY")+","+intValue(row,"relayAnchorZ"));
            else links=null;
        }
        // Pure-JDK controls only, never an observation/evidence API.
        Port(String key,String absolute,boolean inside,String host,long g,int p,long k,Links links){this.key=key;this.absolute=absolute;internal=inside;this.host=host;machine=host;proxy="appeng.me.helpers.AENetworkProxy";path="proxy";part="";item="";dim=1048578;x=y=z=rx=ry=rz=0;metaId=side=damage=-1;this.g=g;this.p=p;this.k=k;persisted=true;this.links=links;}
    }
    private static final class Graph {final long tick;final Map<String,Port> ports=new TreeMap<String,Port>();final Map<List<String>,Long> groups=new LinkedHashMap<List<String>,Long>();final Set<Edge> edges=new HashSet<Edge>();Graph(long tick){this.tick=tick;}}
    private static final class Edge {
        final String a,b;final int da,db;final boolean directional;
        Edge(String a,String b,int da,int db,boolean directional){if(a.compareTo(b)>0){String t=a;a=b;b=t;int d=da;da=db;db=d;}this.a=a;this.b=b;this.da=da;this.db=db;this.directional=directional;}
        Edge map(Map<String,String> map){require(map.containsKey(a)&&map.containsKey(b),"edge endpoint has no exact projection");return new Edge(map.get(a),map.get(b),da,db,directional);}
        @Override public boolean equals(Object value){if(!(value instanceof Edge))return false;Edge e=(Edge)value;return a.equals(e.a)&&b.equals(e.b)&&da==e.da&&db==e.db&&directional==e.directional;}
        @Override public int hashCode(){return Arrays.asList(a,b,da,db,directional).hashCode();}
        @Override public String toString(){return a+" -> "+b+" directions="+da+","+db+" nativeDirectional="+directional;}
    }

    /** Explicit pure-JDK model controls; no Minecraft classes, files, World or native fixture are touched. */
    public static void main(String[] args){
        require(Arrays.equals(args,new String[]{"--negative-controls"}),"explicit pure model controls only");
        coordinateEncodingControls();
        final Graph source=new Graph(1),bank=new Graph(2);
        String old="absolute:oldBank",chosen="relative:chosenBank",drive="relative:bankDrive",core="relative:core",relay="relative:relay",isolated="relative:isolated";
        Port oldBank=new Port(old,old,false,CONTROLLER,7,3,-1,null),chosenBank=new Port(chosen,"absolute:chosenBank",true,CONTROLLER,9,3,-1,null);
        source.ports.put(old,oldBank);source.ports.put(core,new Port(core,"absolute:oldCore",true,QUANTUM,7,3,-1,new Links(relay,old,"oldAnchor")));
        source.ports.put(relay,new Port(relay,"absolute:oldRelay",true,RELAY,7,3,-1,new Links(core,old,"oldAnchor")));
        source.ports.put(isolated,new Port(isolated,"absolute:oldIsolated",true,"appeng.tile.misc.TileCharger",8,-1,-1,null));
        source.groups.put(Arrays.asList(core,old,relay),7L);source.groups.put(Arrays.asList(isolated),8L);
        source.edges.add(new Edge(core,relay,6,6,false));source.edges.add(new Edge(relay,old,6,6,false));
        bank.ports.put(chosen,chosenBank);bank.ports.put(drive,new Port(drive,"absolute:bankDrive",true,"appeng.tile.storage.TileDrive",9,3,-1,null));
        bank.groups.put(Arrays.asList(chosen,drive),9L);bank.edges.add(new Edge(chosen,drive,5,4,true));
        final ShareEmbedding model=shareEmbedding(source,bank,oldBank,chosenBank);
        require(model.ports.size()==5&&model.edges.size()==3&&model.groups.size()==2,"positive selected-bank plus new-copy model");
        final Set<String> missing=new HashSet<String>(model.ports);missing.remove("absolute:bankDrive");
        reject(new Runnable(){public void run(){same(model.ports,missing,"lost retained bank member");}});
        final Set<String> extra=new HashSet<String>(model.ports);extra.add("absolute:oldCore");
        reject(new Runnable(){public void run(){same(model.ports,extra,"unselected original source port");}});
        final Set<Edge> directions=new HashSet<Edge>(model.edges);directions.remove(new Edge("absolute:chosenBank","absolute:bankDrive",5,4,true));directions.add(new Edge("absolute:chosenBank","absolute:bankDrive",4,5,true));
        reject(new Runnable(){public void run(){same(model.edges,directions,"changed native direction");}});
        reject(new Runnable(){public void run(){same(model.groups,Collections.<List<String>>emptySet(),"split/missing member group");}});
        final Set<Long> oldIds=new HashSet<Long>(Arrays.asList(7L,8L,9L));requireGroupId(true,9,9,oldIds);requireGroupId(false,10,9,oldIds);
        reject(new Runnable(){public void run(){requireGroupId(true,10,9,oldIds);}});
        reject(new Runnable(){public void run(){requireGroupId(false,8,9,oldIds);}});
        final Port retained=chosenBank;
        reject(new Runnable(){public void run(){retainedIdentity(retained,new Port(retained.key,retained.absolute,true,CONTROLLER,10,3,-1,null));}});
        final Port original=source.ports.get(isolated);newIdentity(original,new Port(isolated,"unused",true,"appeng.tile.misc.TileCharger",10,3,-1,null),3);
        reject(new Runnable(){public void run(){newIdentity(original,new Port(original.key,"unused",true,original.host,10,4,-1,null),3);}});
        reject(new Runnable(){public void run(){newIdentity(original,new Port(original.key,"unused",true,original.host,10,3,99,null),3);}});
        System.out.println("SHARE pure model: positive selected-bank embedding and 9 rejection controls; no native/whole-world PASS");
    }
    private static void reject(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=true;}require(rejected,"negative control was accepted");}
    private static <T> void same(Set<T> expected,Set<T> actual,String scope){if(expected.equals(actual))return;Set<T> missing=new HashSet<T>(expected);missing.removeAll(actual);Set<T> extra=new HashSet<T>(actual);extra.removeAll(expected);require(false,scope+" differs: missing="+missing.size()+" unexpected="+extra.size());}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalStateException("AE SHARE proof UNKNOWN/rejected: "+reason);}
    @SuppressWarnings("unchecked") private static Set<String> rawSet(String name)throws Exception{return (Set<String>)field(Class.forName(RAW),name);}
    private static String canonicalHash(Object snapshot)throws Exception {byte[] bytes=(byte[])call(Class.forName("gtmb.v0111.probe.CompositeWorldWitness"),"canonicalBytes",snapshot);StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
    private static Object raw(String name,Object... args)throws Exception{return call(Class.forName(RAW),name,args);}
    private static Object typed(Object tag,String key,int type)throws Exception{return raw("typed",tag,key,type);}
    private static Object child(Object tag,String key)throws Exception{return typed(tag,key,10);}
    private static String string(Object tag,String key)throws Exception{return (String)raw("string",tag,key);}
    private static int intValue(Object tag,String key)throws Exception{return (Integer)raw("intValue",tag,key);}
    private static long longValue(Object tag,String key)throws Exception{return (Long)raw("longValue",tag,key);}
    private static boolean flag(Object tag,String key)throws Exception{return (Boolean)raw("flag",tag,key);}
    private static boolean has(Object tag,String key)throws Exception{return (Boolean)raw("has",tag,key);}
    private static List<?> elements(Object tag,String key,int type)throws Exception{return (List<?>)raw("elements",tag,key,type);}
    private static Object compound()throws Exception{return raw("compound");}
    private static Object list()throws Exception{return raw("list");}
    private static Object copy(Object tag)throws Exception{return raw("copy",tag);}
    private static Object strings(List<String> values)throws Exception{return raw("strings",values);}
    private static void text(Object tag,String key,String value)throws Exception{raw("text",tag,key,value);}
    private static void integer(Object tag,String key,int value)throws Exception{raw("integer",tag,key,value);}
    private static void number(Object tag,String key,long value)throws Exception{raw("number",tag,key,value);}
    private static void bool(Object tag,String key,boolean value)throws Exception{raw("bool",tag,key,value);}
    private static void tag(Object tag,String key,Object value)throws Exception{raw("tag",tag,key,value);}
    private static void append(Object list,Object value)throws Exception{raw("append",list,value);}
    private static Set<String> names(String... names){return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(names)));}
    private static Object field(Object object,String name)throws Exception {Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object instanceof Class?null:object);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(type.getName()+"."+name);}
    private static Object call(Object object,String name,Object... args)throws Exception {Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args)){m.setAccessible(true);try{return m.invoke(object instanceof Class?null:object,args);}catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;throw wrapped;}}throw new NoSuchMethodException(type.getName()+"."+name);}
    private static boolean compatible(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<types.length;i++){if(args[i]==null){if(types[i].isPrimitive())return false;continue;}Class<?> t=types[i];if(t.isPrimitive())t=t==int.class?Integer.class:t==long.class?Long.class:t==boolean.class?Boolean.class:t;if(!t.isInstance(args[i]))return false;}return true;}
}
