package gtmb.v0111.probe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

/** Test-only native input/witness. It does not replace any product class or synthesize product DTOs/ACKs. */
@Mod(modid="gtmb_v0111_composite_native_probe", name="GTMB composite real survival witness", version="1")
public final class V0111CompositeNativeProbe {
    private final Properties proof = new Properties(), timing = new Properties();
    private boolean client, server, done, entered, commandStarted;
    private File output;
    private Object mc, actor, bridge, manifest;
    private int phase, ticks, stage, choice, flow, requestSequence, acknowledged, groupIndex;
    private long started, phaseStarted, heartbeat, generation = -1, longFlowStarted;
    private long wholeFlowStarted, processingStarted;
    private String fixtureRequest = "", fixtureKind = "", cutRequest = "", rawId = "";
    private Object lastCut;
    private Consumer<Object> originalCut;
    private Consumer<Object> originalProgress;
    private boolean cutWitnessInstalled, progressWitnessInstalled;
    private final List<Object> incomingCuts = java.util.Collections.synchronizedList(new ArrayList<Object>());
    private final List<Object> incomingCutProgress = java.util.Collections.synchronizedList(new ArrayList<Object>());
    private int rawPreviewProgressStart;
    private int observedCuts;
    private boolean seedMode, prepareOnly;
    private String clientVerdict="";
    private String scenario="all";
    private boolean clientDimensionMappingOwned, clientDimensionAdmitted;
    private boolean productClientMappingLifecycle;
    private int clientDimensionId, clientProviderId;
    private long clientDimensionCleanupStarted;
    private String rawPreviewRequest = "";
    private Object rawRenderStateWitness, rawRenderSession;
    private long rawPreviewStarted, rawRenderStarted, rawFramesBefore;
    private int rawNaturalFrames, rawWorldEvents, rawGlStateChecks, rawGlStateMismatches;
    private static final long PHASE_LIMIT = 1800_000_000_000L, OVERALL_LIMIT = 10800_000_000_000L;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        client = Boolean.getBoolean("gtmb.v0111.compositeClient"); server = Boolean.getBoolean("gtmb.v0111.compositeServer");
        seedMode = Boolean.getBoolean("gtmb.v0111.seedMode"); prepareOnly = Boolean.getBoolean("gtmb.v0111.prepareOnly");
        scenario=System.getProperty("gtmb.v0111.scenario","all");check(Arrays.asList("all","self","share","raw").contains(scenario),"explicit canonical scenario");flow=scenario.equals("share")?1:0;
        if (!client && !server) return;
        check(client != server, "one explicit observer side");
        File fixture = new File(System.getProperty("gtmb.hologram.followupRoot", "")).getCanonicalFile();
        check(fixture.getPath().replace('\\','/').endsWith("/build/hologram-packaged-v018-mui"), "owned isolated fixture root");
        check(new File(".").getCanonicalFile().equals(new File(fixture, client ? "client" : "server").getCanonicalFile()), "exact owned working directory");
        output = new File(System.getProperty("gtmb.v0111.output", "")).getCanonicalFile();
        check(output.toPath().startsWith(new File(fixture.getParentFile(), "verification/render-v0111").getCanonicalFile().toPath()) && output.isDirectory(), "fresh owned evidence output");
        check(!file(client ? "composite-client.properties" : "composite-server.properties").exists(), "fresh observer receipts");
        started = phaseStarted = System.nanoTime();
        proof.setProperty("scope", "40x40x40 actual world; real Netty area import/processing/Save/placement/raw cut; natural rendered MUI stage choices and Save; independently checked client JSON/PNG/owner commit and server material/world oracle; no OS input");
        proof.setProperty("requestedCells", "64000"); proof.setProperty("preferencesRestored", "true"); proof.setProperty("configRestored", "true");
        proof.setProperty("scenario",scenario);
        FMLCommonHandler.instance().bus().register(this);
        if (client) MinecraftForge.EVENT_BUS.register(new RawWorldListener(this));
        persist("RUNNING");
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (!server || done || event.phase != TickEvent.Phase.END) return;
        try {
            check(System.nanoTime() - started < OVERALL_LIMIT, "bounded server overall deadline");
            Object host = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
            List<?> players = (List<?>) get(call(host,"func_71203_ab"),"field_72404_b");
            check(players.size() <= 1, "one configured isolated test actor");
            if (players.isEmpty()) return;
            actor = players.get(0);
            if (seedMode && bridge == null) {
                manifest = readNbt("composite-manifest.nbt"); Object seedReceipt = readNbt("composite-seed-receipt.nbt");
                check(manifest != null && seedReceipt != null, "runner copied exact seed manifest and native terminal receipt");
                Object world = get(actor,"field_70170_p");
                check((Integer)get(actor,"field_71093_bK") == integer(manifest,"dimension"), "normal login restored exact actual seed actor dimension");
                loadFixture(world, "source");
                bridge = new CompositeWorldWitness(world,actor,manifest,seedReceipt);
                commandStarted = true;
            }
            if (!commandStarted) {
                // Local config is the only opt-in. The ordinary level-four console command cannot enable the lab.
                call(call(host,"func_71187_D"),"func_71556_a",host,"gtmbtestlab run composite");
                commandStarted = true; persist("RUNNING"); return;
            }
            if (bridge == null) {
                bridge = call(Class.forName("com.miaokatze.gtmb.hologram.HologramCompositeLabBridge"),"current");
                if (bridge == null) return;
            }
            if (seedMode) call(bridge,"tick");
            Object report = call(bridge,"report");
            if (!"true".equals(proof.getProperty("fixtureReady")) && bool(report,"actualNetty") && (Boolean)call(bridge,"ready")) {
                if (!seedMode) manifest = call(Class.forName("com.miaokatze.gtmb.testlab.TestLab"),"compositeManifest");
                check(Arrays.equals(ints(manifest,"size"),new int[]{40,40,40}),"exact canonical manifest");
                writeNbt("composite-manifest.nbt",manifest);
                proof.setProperty("fixtureReady","true"); proof.setProperty("actualSourceBlocks","64000");
                proof.setProperty("actor",String.valueOf(call(actor,"func_110124_au")));
                proof.setProperty("fixtureSourceSha256",str(report,"sourceSha256")); persist("RUNNING");
                proof.setProperty("fixtureStaticSourceSha256",str(report,"staticSourceSha256"));
                if (prepareOnly) {
                    Object receipt = call(report,"func_74737_b"), bankReceipt=call(get(bridge,"bank"),"receipt");
                    call(receipt,"func_74782_a","paymentReceipt",bankReceipt);
                    call(receipt,"func_74782_a","paymentTerminal",compound(bankReceipt,"terminal"));
                    Object actorNbt=nbt();call(actor,"func_70109_d",actorNbt);
                    writeNbt("composite-actor.nbt",actorNbt); writeNbt("composite-seed-receipt.nbt",receipt);
                    proof.setProperty("seedArtifactsWritten","true");persist("RUNNING");
                }
            }
            Properties command = read("fixture-request.properties");
            int sequence = Integer.parseInt(command.getProperty("sequence","0"));
            if (sequence > acknowledged && manifest != null) {
                check(sequence == acknowledged + 1,"fixture control sequence must be contiguous");
                String action = command.getProperty("action",""), kind = command.getProperty("kind","");
                if (action.equals("encode")) call(bridge,"checkEncoded",kind);
                else if (action.equals("move")) move(host,kind);
                else if (action.equals("fund")) call(bridge,"fundPlacement",kind);
                else if (action.equals("check")) call(bridge,"checkPlacement",kind);
                else if (action.equals("rawCheck")) call(bridge,"checkRaw");
                else if (action.equals("complete")) {if(seedMode&&!scenario.equals("all"))call(bridge,"completeScenario",scenario);else call(bridge,"complete");}
                else throw new IllegalStateException("Unknown fixture-only control: "+action);
                acknowledged = sequence; proof.setProperty("fixtureAcknowledged",String.valueOf(sequence)); persist("RUNNING");
            }
            report = call(bridge,"report"); writeNbt("composite-server-oracle.nbt",report);
            for (String kind : new String[]{"self","share","raw"}) {
                proof.setProperty(kind+"Passed",String.valueOf(bool(report,kind+"Passed")));
                Object commit = compound(report,kind+"SaveCommit");
                if (bool(commit,"ownerCommit")) {
                    proof.setProperty(kind+"SaveCommit","true"); proof.setProperty(kind+"LocalId",str(commit,"localId"));
                    proof.setProperty(kind+"JsonSha256",str(commit,"jsonSha256")); proof.setProperty(kind+"PngSha256",str(commit,"pngSha256"));
                }
            }
            Properties peer=read("composite-client.properties");
            if (prepareOnly && "PREPARED".equals(peer.getProperty("status"))) {
                call(call(host,"func_71187_D"),"func_71556_a",host,"gtmbtestlab stop");
                proof.setProperty("clientVerdict","PREPARED");persist("PREPARED");done=true;call(host,"func_71263_m");return;
            }
            if ("FAIL".equals(peer.getProperty("status"))) throw new IllegalStateException("Client: "+peer.getProperty("failure"));
            if ("PASS".equals(peer.getProperty("status"))) {
                check("PASS".equals(str(report,"status")),"server independent oracle complete PASS");
                if(seedMode){call(bridge,"close");writeNbt("composite-server-oracle.nbt",call(bridge,"report"));}
                proof.setProperty("clientVerdict","PASS"); persist("COMPLETE"); done=true; call(host,"func_71263_m"); return;
            }
            pulse();
        } catch(Throwable failure) { fail(failure); }
    }

    private void move(Object host,String kind) throws Exception {
        check(Arrays.asList("source","self","share","raw").contains(kind),"fixed owned facility move");
        Object world=get(actor,"field_70170_p");
        if(seedMode)loadFixture(world,kind);
        else call(Class.forName("com.miaokatze.gtmb.testlab.TestLab"),"useCompositeFacility",world,actor,kind);
        int[] at;
        double x,y,z;
        if(kind.equals("source")) { at=ints(manifest,"bank"); x=at[0]+2.5; y=at[1]+1; z=at[2]+20.5; }
        else if(seedMode){at=ints(manifest,kind);x=at[0]+.5;y=at[1]+40;z=at[2]+.5;}
        else { at=ints(manifest,kind.equals("self")?"source":kind.equals("share")?"self":"share"); x=at[0]+20.5; y=at[1]+40; z=at[2]+39.5; }
        call(get(actor,"field_71135_a"),"func_147364_a",x,y,z,180f,0f);
        call(call(host,"func_71203_ab"),"func_72358_d",actor);
    }

    private void loadFixture(Object world,String kind)throws Exception {
        for(String name:new String[]{"source","bank",kind}) {int[] at=ints(manifest,name);call(product("HologramCutChunks"),"load",world,at[0],at[2],name.equals("bank")?32:40,name.equals("bank")?32:40);}
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if(!client||done||event.phase!=TickEvent.Phase.END)return;
        try {
            check(System.nanoTime()-started<OVERALL_LIMIT,"bounded overall client deadline");
            check(System.nanoTime()-phaseStarted<PHASE_LIMIT,"phase timeout "+phase);
            Properties peer=read("composite-server.properties");
            if(!clientVerdict.isEmpty()) {if("COMPLETE".equals(peer.getProperty("status"))||"PREPARED".equals(peer.getProperty("status"))){boolean cleaned=disconnectAndReleaseClientDimension();persist(clientVerdict);if(cleaned){done=true;call(mc,"func_71400_g");}}return;}
            check(!"FAIL".equals(peer.getProperty("status")),"server failure: "+peer.getProperty("failure",""));
            if(mc==null)mc=call(Class.forName("net.minecraft.client.Minecraft"),"func_71410_x");
            Object gui=get(mc,"field_71462_r"), player=get(mc,"field_71439_g"), world=get(mc,"field_71441_e"); ticks++;
            if(phase==0) {
                if(ticks<30||gui==null)return;
                admitClientDimension();
                Object fml=call(Class.forName("cpw.mods.fml.client.FMLClientHandler"),"instance");call(fml,"setupServerList");
                call(fml,"connectToServer",gui,construct("net.minecraft.client.multiplayer.ServerData","GTMB actual composite survival","127.0.0.1:25678"));
                advance(1);return;
            }
            if(world==null||player==null) { check(!entered,"lost actual test connection/world");pulse();return; }
            entered=true;
            if(phase==1) {
                if(!"true".equals(peer.getProperty("fixtureReady")))return;
                if(prepareOnly&&!"true".equals(peer.getProperty("seedArtifactsWritten")))return;
                manifest=readNbt("composite-manifest.nbt");if(manifest==null)return;
                check((Integer)get(player,"field_71093_bK")==integer(manifest,"dimension"),"actual player entered opted-in lab dimension");
                verifyClientDimensionAfterConnection();
                if(prepareOnly){persist("PREPARED");clientVerdict="PREPARED";return;}
                check(!(Boolean)call(call(call(mc,"func_147114_u"),"func_147298_b"),"func_150731_c"),"real dedicated Netty socket");
                Object pipeline=call(call(call(call(mc,"func_147114_u"),"func_147298_b"),"channel"),"pipeline");
                call(pipeline,"replace","timeout","timeout",construct("io.netty.handler.timeout.ReadTimeoutHandler",300));
                proof.setProperty("benchmarkSocketReadTimeoutSeconds","300");
                installCutWitness(); fixture("move",scenario.equals("raw")?"raw":"source");advance(scenario.equals("raw")?30:2);return;
            }
            if(phase==2) {
                if(!ack(peer))return;
                call(clientType("HologramClient"),"beginAreaSelection"); wholeFlowStarted=longFlowStarted=System.nanoTime();advance(3);return;
            }
            // A standalone clipboard flow has no ordinary blueprint state to hydrate.
            if(phase>=30) {raw(peer);if(clientVerdict.isEmpty())pulse();return;}
            Object state=get(clientType("HologramClient"),"state"), data=state==null?null:get(state,"data");
            if(state==null)return;
            if(phase==3) {
                Object local=localSelection(data);if(!bool(local,"areaSelecting"))return;
                int[] at=ints(manifest,"source");point(1,at[0],at[1],at[2]);advance(4);return;
            }
            if(phase==4) {
                Object local=localSelection(data);if(ints(local,"areaPoints").length<3)return;
                int[] at=ints(manifest,"source");point(2,at[0]+39,at[1]+39,at[2]+39);advance(5);return;
            }
            if(phase==5) {
                if(ints(localSelection(data),"areaPoints").length!=6)return;
                call(clientType("HologramClient"),"worldAction","finishAreaSelection");advance(6);return;
            }
            if(phase==6) {
                if(!bool(data,"areaImported")||bool(data,"areaImporting")||bool(data,"capturePending")||busy())return;
                check(((List<?>)get(state,"cells")).size()==64000,"exact authoritative full client 64k hydration");
                timing.setProperty(kind()+".importNanos",String.valueOf(System.nanoTime()-longFlowStarted));
                proof.setProperty("actualCapturedCells","64000"); advance(7);return;
            }
            if(phase==12) {
                if(!"true".equals(peer.getProperty(kind()+"SaveCommit"))) {
                    if(isScreen(gui)) {Object ui=call(gui,"smokeLibraryUiState");long gen=number(ui,"renderGeneration");if(bool(ui,"aeReview")&&gen>generation)click(gui,ui,181,gen);}
                    return;
                }
                verifyPair(peer,kind());timing.setProperty(kind()+".saveNanos",String.valueOf(System.nanoTime()-longFlowStarted));
                fixture("move",kind());advance(13);return;
            }
            if(phase==13) {
                if(!ack(peer))return;
                Object request=nbt();putText(request,"presetId",str(data,"blueprintSavedId"));
                check(!str(request,"presetId").isEmpty(),"saved production private preset identity");
                uiSend(gui,"choosePlacementPreset",request);longFlowStarted=System.nanoTime();groupIndex=0;advance(14);return;
            }
            if(phase==14) {
                if(!bool(data,"virtualPlacement")||bool(data,"virtualLocked")||busy())return;
                Object bindings=compound(data,"networkBindings"), groups=list(bindings,"groups");
                if(flow==1&&groupIndex<count(groups)) {
                    Object group=at(groups,groupIndex++);Object request=nbt();putText(request,"group",str(group,"id"));putInt(request,"slot",33);
                    networkOp(state,"bindBlueprintNetwork",request);return;
                }
                int[] target=ints(manifest,kind());Object request=worldRequest(state,"confirmVirtualPosition");
                putInt(request,"x",target[0]);putInt(request,"y",target[1]);putInt(request,"z",target[2]);putInt(request,"side",1);
                call(product("HologramNetwork"),"sendAction",request);advance(15);return;
            }
            if(phase==15) {if(!bool(data,"virtualLocked")||busy())return;fixture("fund",kind());advance(16);return;}
            if(phase==16) {
                if(!ack(peer))return;
                Object request=worldRequest(state,"useAir");call(product("HologramNetwork"),"sendAction",request);advance(17);return;
            }
            if(phase==19) {
                if(integer(data,"job")!=3||busy())return;
                timing.setProperty(kind()+".placementNanos",String.valueOf(System.nanoTime()-longFlowStarted));timing.setProperty(kind()+".longFlowProductCompleteNanos",String.valueOf(System.nanoTime()-wholeFlowStarted));fixture("check",kind());advance(20);return;
            }
            if(phase==20) {
                if(!ack(peer)||!"true".equals(peer.getProperty(kind()+"Passed")))return;
                proof.setProperty(kind()+"PaidWorldVerified","true");
                timing.setProperty(kind()+".longFlowVerifiedNanos",String.valueOf(System.nanoTime()-wholeFlowStarted));
                if(!scenario.equals("all")){fixture("complete",scenario);advance(34);return;}
                if(flow==0){flow=1;stage=choice=0;fixture("move","source");advance(2);}
                else {fixture("move","raw");advance(30);}return;
            }
            if(!isScreen(gui)) {if(phase>=7&&phase<=11)call(clientType("HologramClient"),"openSettings");return;}
            Object ui=call(gui,"smokeLibraryUiState");long gen=number(ui,"renderGeneration");
            if(gen<=generation||gen==0||busy())return;
            if(phase==7) {
                if(!bool(ui,"blueprintTab")||!bool(ui,"library")){click(gui,ui,149,gen);return;}
                processingStarted=System.nanoTime();click(gui,ui,220,gen);stage=choice=0;advance(8);
            } else if(phase==8) {
                if(!bool(ui,"processingFlow"))return;
                if(!bool(ui,"flowFullReview")){click(gui,ui,296,gen);return;}
                check(integer(ui,"flowStage")==stage,"actual ordered processing stage "+stage);
                if(choice==0){if(stage==0)click(gui,ui,flow==0?232:233,gen);choice=1;}
                else if(choice==1){int[] controls={235,flow==0?240:246,flow==0?262:260,273,280,flow==0?290:291};click(gui,ui,controls[stage],gen);choice=2;}
                else {check(bool(ui,"flowChosen"),"explicit actual stage choice");click(gui,ui,231,gen);stage++;choice=0;if(stage==6)advance(9);}
            } else if(phase==9) {
                if(!bool(data,"blueprintProcessingComplete")||bool(ui,"processingFlow"))return;
                timing.setProperty(kind()+".processingNanos",String.valueOf(System.nanoTime()-processingStarted));
                fixture("encode",kind());advance(10);
            } else if(phase==10) {
                if(!ack(peer))return;
                typeName(gui,"V0111 "+kind()+" "+output.getName());generation=gen;advance(11);
            } else if(phase==11) {
                longFlowStarted=System.nanoTime();click(gui,ui,140,gen);
                proof.setProperty(kind()+"SaveButton","140 actual MUI dispatch");advance(12);
            } else if(phase==17) {
                if(!bool(data,"blueprintAwaitingConfirmation"))return;
                click(gui,ui,50,gen);advance(18);
            } else if(phase==18) {click(gui,ui,55,gen);advance(19);}
            pulse();
        } catch(Throwable failure) {fail(failure);}
    }

    private void raw(Properties peer)throws Exception {
        if(phase==30){
            if(!ack(peer))return;
            Object cut=get(clientType("HologramCutClient"),"INSTANCE");
            if((Boolean)call(cut,"busy"))return;
            Object request=nbt();putText(request,"action","cut");cutRequest=java.util.UUID.randomUUID().toString();rawId=cutRequest;
            putText(request,"requestId",cutRequest);putText(request,"name","V0111 actual raw "+output.getName());int[] at=ints(manifest,"source");
            for(int axis=0;axis<3;axis++){putInt(request,new String[]{"x1","y1","z1"}[axis],at[axis]);putInt(request,new String[]{"x2","y2","z2"}[axis],at[axis]+39);}
            wholeFlowStarted=longFlowStarted=System.nanoTime();
            // Use the product request tracker as well as its real Netty transport. Its receiver owns the directory.
            call(cut,"send",request);advance(31);return;
        }
        if(phase==31){
            Object reply=cutReply(cutRequest);if(reply==null)return;
            check(bool(reply,"success"),str(reply,"message"));check(rawId.equals(str(reply,"id")),"actual raw clipboard identity");
            check(count(list(compound(reply,"preview"),"cells"))==64000,"actual complete selected raw 64k payload");
            proof.setProperty("actualCapturedCells","64000");timing.setProperty("raw.cutNanos",String.valueOf(System.nanoTime()-longFlowStarted));
            rawPreviewStarted=System.nanoTime();advance(35);return;
        }
        if(phase==35){
            Object cut=get(clientType("HologramCutClient"),"INSTANCE");
            if((Boolean)call(cut,"busy"))return;
            check((Boolean)call(cut,"containsEntry",rawId),"native cut ACK installed the actual clipboard directory entry");
            Object player=get(mc,"field_71439_g");
            // Camera only: keep the server's target/paste request independent of the moving preview anchor.
            set(player,"field_70177_z",-45f);set(player,"field_70126_B",-45f);
            set(player,"field_70125_A",25f);set(player,"field_70127_C",25f);
            proof.setProperty("raw.previewCamera","yaw=-45 pitch=25; native moving anchor; paste uses manifest target");
            rawPreviewProgressStart=incomingCutProgress.size();
            call(cut,"beginStoragePlacement",rawId);
            check((Boolean)call(cut,"active")&&rawId.equals(call(cut,"selected")),"actual storage action entered production moving-preview activity");
            Object requests=get(get(cut,"requests"),"requests");
            for(Object request:((java.util.Map<?,?>)requests).values()) {
                if("preview".equals(get(request,"action"))&&rawId.equals(get(request,"target"))) {
                    check(rawPreviewRequest.isEmpty(),"one native preview request for the selected clipboard");rawPreviewRequest=(String)get(request,"id");
                }
            }
            check(!rawPreviewRequest.isEmpty()&&!rawPreviewRequest.equals(cutRequest),"storage action issued a distinct real native preview request");
            proof.setProperty("raw.previewRequestId",rawPreviewRequest);advance(36);return;
        }
        if(phase==36){
            Object reply=cutReply(rawPreviewRequest);if(reply==null)return;
            check(bool(reply,"success"),"native raw preview: "+str(reply,"message"));
            check(rawId.equals(str(reply,"id")),"native preview reply belongs to the selected cut");
            check(count(list(compound(reply,"preview"),"cells"))==64000,"real preview transport reassembled and decoded all 64000 cells");
            Object cut=get(clientType("HologramCutClient"),"INSTANCE"), installed=get(cut,"rawPreview");
            if(installed==null||!rawId.equals(get(cut,"previewId")))return;
            if(rawRenderStarted==0){
                Object snapshot=call(clientType("HologramCutRawPreview"),"snapshot");
                check(count(list(installed,"cells"))==64000&&count(list(snapshot,"cells"))==64000,"native client and world renderer retain complete 64k raw payloads");
                check(installed.equals(compound(reply,"preview"))&&snapshot.equals(installed),"renderer input is the exact native preview reply without tool reconstruction");
                Object transport=nbt(), fragments=construct("net.minecraft.nbt.NBTTagList");
                boolean complete=false;long bytes=0;int observed=0;
                synchronized(incomingCutProgress){
                    for(int index=rawPreviewProgressStart;index<incomingCutProgress.size();index++){
                        Object progress=incomingCutProgress.get(index);call(fragments,"func_74742_a",call(progress,"func_74737_b"));observed++;
                        if(bool(progress,"complete")&&number(progress,"done")==number(progress,"total")){complete=true;bytes=Math.max(bytes,number(progress,"total"));}
                    }
                }
                check(complete&&observed>0&&bytes>0,"native preview fragment observer reached actual byte completion before decoded 64k reply");
                call(transport,"func_74782_a","fragments",fragments);writeNbt("composite-raw-preview-transport.nbt",transport);
                proof.setProperty("raw.previewObservedFragments",String.valueOf(observed));proof.setProperty("raw.previewEncodedBytes",String.valueOf(bytes));
                writeNbt("composite-raw-preview-reply.nbt",reply);
                proof.setProperty("raw.previewDecodedCells","64000");
                rawRenderStarted=System.nanoTime();timing.setProperty("raw.previewHydrationNanos",String.valueOf(rawRenderStarted-rawPreviewStarted));
            }
            Object inspection=call(clientType("HologramCutRawPreview"),"inspection");
            check(integer(inspection,"cells")==64000,"production raw renderer keeps all 64000 cells");
            if(integer(inspection,"failureCount")!=0||!str(inspection,"ghostFailure").isEmpty()||!str(inspection,"ghostRenderFailure").isEmpty())writeNbt("composite-raw-preview-render.nbt",inspection);
            check(integer(inspection,"failureCount")==0,"native raw renderer failures: "+inspection);
            check(str(inspection,"ghostFailure").isEmpty()&&str(inspection,"ghostRenderFailure").isEmpty(),"native ghost render failure: "+inspection);
            if(rawNaturalFrames<8||integer(inspection,"pendingVisibleCells")!=0)return;
            check(number(inspection,"staticRendered")>0&&integer(inspection,"displayLists")>0,"natural WorldLast compiled real visible native block geometry");
            check(number(inspection,"ghostComposites")>=8,"natural WorldLast executed native ghost compositing on multiple frames");
            check(rawGlStateChecks>=8,"native CTM state witness observed multiple natural world frames");
            writeNbt("composite-raw-preview-render.nbt",inspection);
            proof.setProperty("raw.previewWorldLastEvents",String.valueOf(rawWorldEvents));
            proof.setProperty("raw.previewNaturalRendererFrames",String.valueOf(rawNaturalFrames));
            proof.setProperty("raw.previewGlStateChecks",String.valueOf(rawGlStateChecks));
            proof.setProperty("raw.previewGlStateMismatches",String.valueOf(rawGlStateMismatches));
            proof.setProperty("raw.previewGlObservationScope","HIGHEST-to-LOWEST whole Forge WorldLast event using native CTM StateWitness; other mods are included, so mismatches are recorded without sole-renderer attribution");
            proof.setProperty("raw.previewRenderScope","real 64000-cell native WORLD session; eight or more natural WorldLast frames and completed visible native geometry; visibility-culling still applies; no direct renderer calls or pixel PASS inference");
            timing.setProperty("raw.previewRenderNanos",String.valueOf(System.nanoTime()-rawRenderStarted));
            timing.setProperty("raw.previewNanos",String.valueOf(System.nanoTime()-rawPreviewStarted));
            // A preview must be observed before this authoritative write is allowed to leave the client.
            Object request=nbt();putText(request,"action","paste");cutRequest=java.util.UUID.randomUUID().toString();
            putText(request,"requestId",cutRequest);putText(request,"id",rawId);int[] at=ints(manifest,"raw");
            putInt(request,"originX",at[0]);putInt(request,"originY",at[1]);putInt(request,"originZ",at[2]);
            longFlowStarted=System.nanoTime();call(cut,"send",request);advance(32);return;
        }
        if(phase==32){Object reply=cutReply(cutRequest);if(reply==null)return;check(bool(reply,"success"),str(reply,"message"));timing.setProperty("raw.pasteNanos",String.valueOf(System.nanoTime()-longFlowStarted));timing.setProperty("raw.longFlowProductCompleteNanos",String.valueOf(System.nanoTime()-wholeFlowStarted));fixture("rawCheck","raw");advance(33);return;}
        if(phase==33){if(!ack(peer)||!"true".equals(peer.getProperty("rawPassed")))return;timing.setProperty("raw.longFlowVerifiedNanos",String.valueOf(System.nanoTime()-wholeFlowStarted));fixture("complete","");advance(34);return;}
        if(phase==34){if(!ack(peer))return;proof.setProperty("scenarioVerified",scenario);if(scenario.equals("all")||scenario.equals("raw"))proof.setProperty("rawPaidWorldVerified","true");restoreCutWitness();persist("PASS");clientVerdict="PASS";}
    }

    /** Observe the actual product subscriber; this listener never invokes update/render or installs a session. */
    private void rawWorldBefore() {
        rawRenderStateWitness=null;
        if(!client||done||phase!=36)return;
        try {
            Object cut=get(clientType("HologramCutClient"),"INSTANCE");
            if(get(mc,"field_71462_r")!=null||!rawId.equals(get(cut,"previewId"))||get(cut,"rawPreview")==null)return;
            rawRenderSession=get(clientType("HologramCutRawPreview"),"WORLD");
            rawFramesBefore=((Number)get(rawRenderSession,"frames")).longValue();
            rawRenderStateWitness=construct("com.miaokatze.gtmb.client.hologram.HologramCutRawPreviewScope$StateWitness");
        } catch(Throwable failure){fail(failure);}
    }

    private void rawWorldAfter() {
        if(rawRenderStateWitness==null||done||phase!=36)return;
        try {
            rawWorldEvents++;
            if(((Number)get(rawRenderSession,"frames")).longValue()>rawFramesBefore)rawNaturalFrames++;
            rawGlStateChecks++;
            if(!(Boolean)call(rawRenderStateWitness,"matches"))rawGlStateMismatches++;
            proof.setProperty("raw.previewWorldLastEvents",String.valueOf(rawWorldEvents));
            proof.setProperty("raw.previewNaturalRendererFrames",String.valueOf(rawNaturalFrames));
            proof.setProperty("raw.previewGlStateChecks",String.valueOf(rawGlStateChecks));
            proof.setProperty("raw.previewGlStateMismatches",String.valueOf(rawGlStateMismatches));
        } catch(Throwable failure){fail(failure);}
        finally{rawRenderStateWitness=null;}
    }

    /** Client event types stay outside the dedicated-server listener's declared methods. */
    public static final class RawWorldListener {
        private final V0111CompositeNativeProbe owner;
        RawWorldListener(V0111CompositeNativeProbe owner){this.owner=owner;}
        @SubscribeEvent(priority=EventPriority.HIGHEST)
        public void before(RenderWorldLastEvent event){owner.rawWorldBefore();}
        @SubscribeEvent(priority=EventPriority.LOWEST)
        public void after(RenderWorldLastEvent event){owner.rawWorldAfter();}
    }

    private Object localSelection(Object data)throws Exception {try{return call(clientType("HologramClient"),"localAreaSelection");}catch(NoSuchMethodException old){return data;}}

    /** Historical TestLab.init registers a provider but leaves its dimension mapping to serverStarting. */
    private void admitClientDimension()throws Exception {
        if(clientDimensionAdmitted)return;
        check(client&&!server&&!entered,"client-only test dimension admission before any connection world");
        File localConfig=new File("config/gtmb-testlab.cfg").getCanonicalFile();
        check(localConfig.isFile(),"explicit existing local client testlab configuration required");
        Object configuration=construct("net.minecraftforge.common.config.Configuration",localConfig);
        call(configuration,"load");
        Object enabled=call(configuration,"get","testlab","enabled",false);
        check((Boolean)call(enabled,"getBoolean",false),"local client testlab.enabled must explicitly opt in");
        clientDimensionId=(Integer)call(call(configuration,"get","testlab","dimensionId",1048578),"getInt",1048578);
        clientProviderId=(Integer)call(call(configuration,"get","testlab","providerId",1048578),"getInt",1048578);
        check(clientDimensionId==1048578&&clientProviderId==1048578,"fixed owned fixture dimension/provider only");
        Class<?> dimensions=Class.forName("net.minecraftforge.common.DimensionManager");
        Class<?> provider=Class.forName("com.miaokatze.gtmb.testlab.TestLabWorldProvider");
        Object registered=((java.util.Map<?,?>)get(dimensions,"providers")).get(clientProviderId);
        check(registered==provider,"this product version must already register its exact native TestLabWorldProvider");
        Class<?> laboratory=Class.forName("com.miaokatze.gtmb.testlab.TestLab");
        boolean hasConnected=false,hasAvailable=false;
        try{laboratory.getDeclaredMethod("clientConnected",Object.class,boolean.class);hasConnected=true;}catch(NoSuchMethodException historical){}
        try{laboratory.getDeclaredMethod("clientMappingAvailable");hasAvailable=true;}catch(NoSuchMethodException historical){}
        proof.setProperty("clientTestlabConfigSha256",hash(Files.readAllBytes(localConfig.toPath())));
        proof.setProperty("clientDimensionId",String.valueOf(clientDimensionId));proof.setProperty("clientProviderId",String.valueOf(clientProviderId));
        if(hasConnected||hasAvailable) {
            check(hasConnected&&hasAvailable,"new product client dimension lifecycle API must be complete");
            check((Boolean)call(laboratory,"clientMappingAvailable"),"new product reports native client mapping availability");
            productClientMappingLifecycle=true;clientDimensionAdmitted=true;
            proof.setProperty("testOnlyDimensionAdmissionCompensation","false");
            proof.setProperty("clientDimensionMappingOwner","production");
            proof.setProperty("clientDimensionMappingOwnedByAddon","false");
            proof.setProperty("dimensionAdmissionScope","product native connection lifecycle; addon does not call clientConnected or register a mapping; actual mapping checked after connection");
            persist("RUNNING");return;
        }
        if((Boolean)call(dimensions,"isDimensionRegistered",clientDimensionId)) {
            check((Integer)call(dimensions,"getProviderType",clientDimensionId)==clientProviderId,"pre-existing client mapping must match exact native provider");
        } else {
            call(dimensions,"registerDimension",clientDimensionId,clientProviderId);
            clientDimensionMappingOwned=true;
        }
        check((Integer)call(dimensions,"getProviderType",clientDimensionId)==clientProviderId,"actual client mapping registered for native test provider");
        clientDimensionAdmitted=true;
        proof.setProperty("testOnlyDimensionAdmissionCompensation","true");
        proof.setProperty("clientDimensionMappingOwner","addon_historical");
        proof.setProperty("dimensionAdmissionScope","test-only client dimension mapping under explicit local enabled config; existing product provider only; no provider0/Bridge/world creation; business assertions unchanged");
        proof.setProperty("clientDimensionMappingOwnedByAddon",String.valueOf(clientDimensionMappingOwned));
        persist("RUNNING");
    }

    private void verifyClientDimensionAfterConnection()throws Exception {
        Class<?> dimensions=Class.forName("net.minecraftforge.common.DimensionManager");
        check((Boolean)call(dimensions,"isDimensionRegistered",clientDimensionId),"actual connected client dimension mapping exists");
        check((Integer)call(dimensions,"getProviderType",clientDimensionId)==clientProviderId,"actual connected client dimension uses exact native provider");
        check(((java.util.Map<?,?>)get(dimensions,"providers")).get(clientProviderId)==Class.forName("com.miaokatze.gtmb.testlab.TestLabWorldProvider"),"actual connected client provider class is native TestLabWorldProvider");
        if(productClientMappingLifecycle)check(bool(call(Class.forName("com.miaokatze.gtmb.testlab.TestLab"),"status"),"clientDimensionMapped"),"product connection event actually owns the native client mapping");
        proof.setProperty("actualClientDimensionMappingVerified","true");
    }

    private boolean disconnectAndReleaseClientDimension()throws Exception {
        if(!client)return true;
        // Release the client world through its native lifecycle before removing our own mapping.
        if(mc!=null&&get(mc,"field_71441_e")!=null){call(get(mc,"field_71441_e"),"func_72882_A");call(mc,"func_71403_a",(Object)null);}
        if(productClientMappingLifecycle) {
            if(clientDimensionCleanupStarted==0)clientDimensionCleanupStarted=System.nanoTime();
            Object status=call(Class.forName("com.miaokatze.gtmb.testlab.TestLab"),"status");
            if(bool(status,"clientDimensionMapped")) {
                check(System.nanoTime()-clientDimensionCleanupStarted<30_000_000_000L,"product native client disconnection mapping cleanup deadline");
                proof.setProperty("clientDimensionMappingCleanup","awaiting product native disconnection event; addon does not unregister it");return false;
            }
            check(!(Boolean)call(Class.forName("net.minecraftforge.common.DimensionManager"),"isDimensionRegistered",clientDimensionId),"product native disconnection removed its own test mapping");
            proof.setProperty("clientDimensionMappingCleanup","production native disconnection event observed");
            proof.setProperty("clientDimensionMappingCleanupComplete","true");return true;
        }
        if(clientDimensionMappingOwned) {
            Class<?> dimensions=Class.forName("net.minecraftforge.common.DimensionManager");
            if((Boolean)call(dimensions,"isDimensionRegistered",clientDimensionId)) {
                check((Integer)call(dimensions,"getProviderType",clientDimensionId)==clientProviderId,"owned client dimension mapping changed; refuse to remove another mapping");
                check(((java.util.Map<?,?>)get(dimensions,"providers")).get(clientProviderId)==Class.forName("com.miaokatze.gtmb.testlab.TestLabWorldProvider"),"owned mapping still uses exact native provider");
                call(dimensions,"unregisterDimension",clientDimensionId);
            }
            clientDimensionMappingOwned=false;
            proof.setProperty("clientDimensionMappingReleased","true");
        } else proof.setProperty("clientDimensionMappingCleanup","pre-existing historical mapping not modified by addon");
        proof.setProperty("clientDimensionMappingCleanupComplete","true");
        return true;
    }
    private void point(int point,int x,int y,int z)throws Exception {try{check((Boolean)call(clientType("HologramClient"),"localAreaPoint",point,x,y,z,"replace"),"actual local area point");}catch(NoSuchMethodException old){Object state=get(clientType("HologramClient"),"state"),request=worldRequest(state,"areaPoint");putInt(request,"point",point);putInt(request,"x",x);putInt(request,"y",y);putInt(request,"z",z);call(product("HologramNetwork"),"sendAction",request);}}
    private void fixture(String action,String kind)throws Exception {Properties request=new Properties();request.setProperty("sequence",String.valueOf(++requestSequence));request.setProperty("action",action);request.setProperty("kind",kind);store("fixture-request.properties",request);fixtureRequest=action;fixtureKind=kind;}
    private boolean ack(Properties peer){return Integer.parseInt(peer.getProperty("fixtureAcknowledged","0"))==requestSequence;}
    private String kind(){return scenario.equals("raw")?"raw":flow==0?"self":"share";}
    private void uiSend(Object gui,String op,Object request)throws Exception {check(isScreen(gui),"real live MUI session required");call(get(get(gui,"ui"),"session"),"send",op,request,true);}
    private void networkOp(Object state,String op,Object request)throws Exception {putText(request,"op",op);putText(request,"session",String.valueOf(get(state,"session")));call(request,"func_74772_a","planRevision",number(get(state,"data"),"planRevision"));call(product("HologramNetwork"),"sendAction",request);}
    private Object worldRequest(Object state,String action)throws Exception {Object request=nbt();putText(request,"action",action);putText(request,"session",String.valueOf(get(state,"session")));call(request,"func_74772_a","planRevision",number(get(state,"data"),"planRevision"));return request;}

    private void verifyPair(Properties peer,String kind)throws Exception {
        String id=peer.getProperty(kind+"LocalId","");check(id.matches("local_[a-fA-F0-9-]{36}"),"real owner-committed local file ID");
        File game=(File)get(mc,"field_71412_D");Path directory=new File(game,"MiaoBlueprints").getCanonicalFile().toPath();Path json=directory.resolve(id+".json"),png=directory.resolve(id+".png");
        check(Files.isRegularFile(json)&&Files.isRegularFile(png),"actual JSON+PNG on client disk");
        byte[] jsonBytes=Files.readAllBytes(json),pngBytes=Files.readAllBytes(png);
        check(hash(jsonBytes).equals(peer.getProperty(kind+"JsonSha256")),"independent JSON SHA matches exact production owner commit");
        check(hash(pngBytes).equals(peer.getProperty(kind+"PngSha256")),"independent PNG SHA matches exact production owner commit");
        java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(png.toFile());check(image!=null&&image.getWidth()==640&&image.getHeight()==640,"actual native preview image dimensions");
        Object artifact=call(product("HologramBlueprintArtifact"),"readPair",json);
        check(hash(jsonBytes).equals(get(artifact,"jsonSha256")),"paired PNG source-JSON binding validated");
        Object preset=call(product("HologramBlueprintSharing"),"decode",new String(jsonBytes,java.nio.charset.StandardCharsets.UTF_8));
        check(((List<?>)get(preset,"cells")).size()==64000,"actual disk saved exact 64k blueprint cells");
        Files.copy(json,file(kind+"-actual-saved.json").toPath());Files.copy(png,file(kind+"-actual-saved.png").toPath());
        proof.setProperty(kind+"ActualPair","true");proof.setProperty(kind+"JsonBytes",String.valueOf(jsonBytes.length));proof.setProperty(kind+"PngBytes",String.valueOf(pngBytes.length));proof.setProperty(kind+"JsonSha256",hash(jsonBytes));proof.setProperty(kind+"PngSha256",hash(pngBytes));
    }

    @SuppressWarnings("unchecked")
    private void installCutWitness()throws Exception {
        originalCut=(Consumer<Object>)get(product("HologramNetwork"),"cutReceiver");
        member(product("HologramNetwork"),"cutReceiver").set(null,(Consumer<Object>)value->{incomingCuts.add(value);if(originalCut!=null)originalCut.accept(value);});cutWitnessInstalled=true;
        originalProgress=(Consumer<Object>)get(product("HologramNetwork"),"progressReceiver");
        member(product("HologramNetwork"),"progressReceiver").set(null,(Consumer<Object>)value->{
            try{if("cut".equals(str(value,"scope"))&&"download".equals(str(value,"phase")))incomingCutProgress.add(value);}catch(Exception error){throw new IllegalStateException("Cannot observe native cut fragment",error);}
            if(originalProgress!=null)originalProgress.accept(value);
        });progressWitnessInstalled=true;
    }
    private void restoreCutWitness()throws Exception {
        if(client&&cutWitnessInstalled){member(product("HologramNetwork"),"cutReceiver").set(null,originalCut);originalCut=null;cutWitnessInstalled=false;}
        if(client&&progressWitnessInstalled){member(product("HologramNetwork"),"progressReceiver").set(null,originalProgress);originalProgress=null;progressWitnessInstalled=false;}
    }
    private Object cutReply(String request)throws Exception {synchronized(incomingCuts){for(Object reply:incomingCuts)if(request.equals(str(reply,"requestId")))return reply;}return null;}
    private static boolean isScreen(Object gui){return gui!=null&&gui.getClass().getName().equals("com.miaokatze.gtmb.client.hologram.HologramScreen");}
    private void click(Object gui,Object ui,int id,long gen)throws Exception {Object controls=list(ui,"drawnControls");boolean enabled=false;for(int i=0;i<count(controls);i++){Object c=at(controls,i);if(integer(c,"id")==id){enabled=bool(c,"enabled");break;}}check(enabled,"actual visible enabled MUI control "+id);call(gui,"smokeControlPoint",id);call(clientType("HologramMuiProbeInput"),"control",gui,id);generation=gen;proof.setProperty("lastControl",String.valueOf(id));}
    private void typeName(Object gui,String name)throws Exception {Class<?> diag=clientType("HologramUiDiagnostics");Object field=call(diag,"readDiagnosticField",gui,"presetNameField");float scale=(Float)call(diag,"readDiagnosticField",gui,"scale");int x=(Integer)call(diag,"readDiagnosticField",gui,"originX")+Math.round(((Integer)get(field,"xPosition")+4)*scale),y=(Integer)call(diag,"readDiagnosticField",gui,"originY")+Math.round(((Integer)get(field,"yPosition")+7)*scale);call(clientType("HologramMuiProbeInput"),"click",gui,x,y,0);check((Boolean)call(field,"isFocused"),"native name field focus");call(clientType("HologramMuiProbeInput"),"key",gui,(char)1,30);for(char c:name.toCharArray())call(clientType("HologramMuiProbeInput"),"key",gui,c,0);check(name.equals(call(field,"getText")),"exact native keyboard name");}
    private void advance(int next)throws Exception {long now=System.nanoTime();timing.setProperty(kind()+".phase."+phase+".nanos",String.valueOf(now-phaseStarted));phase=next;phaseStarted=now;persist("RUNNING");}
    private void pulse()throws Exception {if(System.nanoTime()-heartbeat<2_000_000_000L)return;heartbeat=System.nanoTime();persist("RUNNING");}
    private void persist(String status)throws Exception {proof.setProperty("status",status);proof.setProperty("phase",String.valueOf(phase));proof.setProperty("flow",kind());proof.setProperty("phaseElapsedNanos",String.valueOf(System.nanoTime()-phaseStarted));proof.setProperty("elapsedNanos",String.valueOf(System.nanoTime()-started));store(client?"composite-client.properties":"composite-server.properties",proof);store(client?"composite-client-timing.properties":"composite-server-timing.properties",timing);}
    private void fail(Throwable failure){while(failure instanceof java.lang.reflect.InvocationTargetException&&failure.getCause()!=null)failure=failure.getCause();failure.printStackTrace();try{proof.setProperty("failure",failure.toString());proof.setProperty("failurePhase",String.valueOf(phase));if(client){try{restoreCutWitness();}catch(Exception cleanup){proof.setProperty("cutWitnessCleanupFailure",cleanup.toString());}try{disconnectAndReleaseClientDimension();}catch(Exception cleanup){proof.setProperty("clientDimensionMappingCleanupFailure",cleanup.toString());}}if(server&&seedMode&&bridge!=null){try{call(bridge,"close");writeNbt("composite-server-oracle.nbt",call(bridge,"report"));}catch(Exception cleanup){proof.setProperty("fixtureCleanupFailure",cleanup.toString());}}persist("FAIL");}catch(Exception nested){nested.printStackTrace();}done=true;try{if(client&&mc!=null)call(mc,"func_71400_g");if(server)call(call(Class.forName("net.minecraft.server.MinecraftServer"),"func_71276_C"),"func_71263_m");}catch(Exception ignored){}}
    private File file(String name){return new File(output,name);}
    private Properties read(String name)throws Exception {Properties p=new Properties();if(file(name).isFile())try(FileInputStream in=new FileInputStream(file(name))){p.load(in);}catch(java.io.FileNotFoundException transientReplacement){return new Properties();}return p;}
    private void store(String name,Properties value)throws Exception {File temporary=file(name+".tmp");try(FileOutputStream stream=new FileOutputStream(temporary)){value.store(stream,"actual native composite witness");stream.getFD().sync();}Files.move(temporary.toPath(),file(name).toPath(),StandardCopyOption.REPLACE_EXISTING);}
    private void writeNbt(String name,Object value)throws Exception {File temporary=file(name+".tmp");call(Class.forName("net.minecraft.nbt.CompressedStreamTools"),"func_74795_b",value,temporary);Files.move(temporary.toPath(),file(name).toPath(),StandardCopyOption.REPLACE_EXISTING);}
    private Object readNbt(String name)throws Exception {return file(name).isFile()?call(Class.forName("net.minecraft.nbt.CompressedStreamTools"),"func_74797_a",file(name)):null;}
    private static Object nbt()throws Exception{return construct("net.minecraft.nbt.NBTTagCompound");}
    private static Object compound(Object tag,String key)throws Exception{return call(tag,"func_74775_l",key);}
    private static Object list(Object tag,String key)throws Exception{return call(tag,"func_150295_c",key,10);}
    private static Object at(Object tag,int index)throws Exception{return call(tag,"func_150305_b",index);}
    private static int count(Object tag)throws Exception{return (Integer)call(tag,"func_74745_c");}
    private static int[] ints(Object tag,String key)throws Exception{return (int[])call(tag,"func_74759_k",key);}
    private static boolean bool(Object tag,String key)throws Exception{return (Boolean)call(tag,"func_74767_n",key);}
    private static int integer(Object tag,String key)throws Exception{return (Integer)call(tag,"func_74762_e",key);}
    private static long number(Object tag,String key)throws Exception{return (Long)call(tag,"func_74763_f",key);}
    private static String str(Object tag,String key)throws Exception{return (String)call(tag,"func_74779_i",key);}
    private static void putText(Object tag,String key,String value)throws Exception{call(tag,"func_74778_a",key,value);}
    private static void putInt(Object tag,String key,int value)throws Exception{call(tag,"func_74768_a",key,value);}
    private static Class<?> product(String name)throws Exception{return Class.forName("com.miaokatze.gtmb.hologram."+name);}
    private static Class<?> clientType(String name)throws Exception{return Class.forName("com.miaokatze.gtmb.client.hologram."+name);}
    private static boolean busy()throws Exception{return (Boolean)call(clientType("HologramBackgroundTasks"),"busy");}
    private static String hash(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",v&255));return out.toString();}
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
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
