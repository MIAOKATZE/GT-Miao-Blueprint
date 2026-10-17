package gtmb.v0111.probe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
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
import cpw.mods.fml.common.network.FMLNetworkEvent;
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
    private Object generationScreen;
    private Object guidanceAwaitingGui;
    private long wholeFlowStarted, processingStarted, constructionStarted;
    private long nativeLabProgressAt;
    private long awaitedTaskToken, awaitedTaskSequence=-1;
    private String awaitedTaskKind="", awaitedTaskSession="";
    private String fixtureRequest = "", fixtureKind = "", cutRequest = "", rawId = "";
    private Object lastCut;
    private Consumer<Object> originalCut;
    private Consumer<Object> originalProgress;
    private Consumer<Object> originalLibraryProbe;
    private boolean cutWitnessInstalled, progressWitnessInstalled, libraryProbeInstalled;
    private final List<Object> incomingCuts = java.util.Collections.synchronizedList(new ArrayList<Object>());
    private final List<Object> incomingCutProgress = java.util.Collections.synchronizedList(new ArrayList<Object>());
    private final List<Object> incomingPrivateReplies = java.util.Collections.synchronizedList(new ArrayList<Object>());
    private String privateLocalId="", privatePreviousPreset="", privateTransfer="";
    private int rawPreviewProgressStart;
    private int observedCuts;
    private boolean seedMode, prepareOnly;
    private String clientVerdict="";
    private String scenario="all";
    private boolean clientDimensionMappingOwned, clientDimensionAdmitted;
    private boolean productClientMappingLifecycle;
    private int clientDimensionId, clientProviderId;
    private long clientDimensionCleanupStarted;
    private boolean clientDisconnectInitiated;
    private Boolean previousPauseOnLostFocus;
    private boolean clientPreferencesRestored;
    private boolean displayActiveUnavailable;
    private volatile Object admittedClientConnection;
    private String rawPreviewRequest = "";
    private Object rawRenderStateWitness, rawRenderSession;
    private long rawPreviewStarted, rawRenderStarted, rawFramesBefore;
    private int rawNaturalFrames, rawWorldEvents, rawGlStateChecks, rawGlStateMismatches;
    private boolean rawPrepareSent;
    private String lightWaitKey="", lightingGlLabel="", lightingGlBefore="";
    private long lightWaitStarted;
    private Object lightingGlWitness;
    private int baselineLightingFrames, previewLightingFrames;
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
        proof.setProperty("requestedSourceVolume", "64000"); proof.setProperty("preferencesRestored", "true"); proof.setProperty("configRestored", "true");
        proof.setProperty("scenario",scenario);
        proof.setProperty("lightingProfile",seedMode?CompositeWorldWitness.LIGHTING_PROFILE:"legacy-dark-v1");
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
            if(!seedMode&&commandStarted)observeNativeLabFailure();
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
            if(seedMode&&!(Boolean)call(bridge,"lightingInstalled")&&"true".equals(read("composite-client.properties").getProperty("clientLightingBeforeSampled")))call(bridge,"installLighting");
            if (seedMode) call(bridge,"tick");
            if(seedMode)persistAeSamples();
            Object report = call(bridge,"report");
            if (!"true".equals(proof.getProperty("fixtureReady")) && bool(report,"actualNetty") && (Boolean)call(bridge,"ready")) {
                if (!seedMode) manifest = call(Class.forName("com.miaokatze.gtmb.testlab.TestLab"),"compositeManifest");
                check(Arrays.equals(ints(manifest,"size"),new int[]{40,40,40}),"exact canonical manifest");
                writeNbt("composite-manifest.nbt",manifest);
                proof.setProperty("fixtureReady","true"); proof.setProperty("actualSourceVolume","64000");
                if(seedMode){
                    writeNbt("composite-source-baseline.nbt",call(bridge,"baselineSnapshot"));
                    int volume=integer(report,"sourceVolume"),nonAir=integer(report,"sourceNonAirBlocks"),expected=integer(report,"expectedBlueprintCells");
                    check(volume==64000&&nonAir>0&&nonAir<=volume&&expected==nonAir,"independent native volume/non-air count is complete before ordinary import");
                    proof.setProperty("actualSourceNonAirBlocks",String.valueOf(nonAir));proof.setProperty("actualSourceAirBlocks",String.valueOf(volume-nonAir));
                    proof.setProperty("expectedBlueprintCells",String.valueOf(expected));proof.setProperty("blueprintCountBasis",str(report,"blueprintCountBasis"));
                }
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
                if (action.equals("move")) move(host,kind);
                else if (action.equals("fund")) call(bridge,"fundPlacement",kind);
                else if (action.equals("check")) call(bridge,"checkPlacement",kind);
                else if (action.equals("rawPrepare")) {check(seedMode,"server-owned durable raw witness requires seeded independent baseline");if(!(Boolean)call(bridge,"prepareRawCut")){writeNbt("composite-server-oracle.nbt",call(bridge,"report"));pulse();return;}persistAeSamples();}
                else if (action.equals("rawCapture")) {try{call(bridge,"captureRawCut",readNbt("composite-raw-cut-ack.nbt"));}finally{persistRawSamples();}}
                else if (action.equals("rawPreviewVerify")) call(bridge,"verifyRawPreview",readNbt("composite-raw-preview-reply.nbt"));
                else if (action.equals("rawCheck")) call(bridge,"checkRaw");
                else if (action.equals("complete")) {if(seedMode&&!scenario.equals("all"))call(bridge,"completeScenario",scenario);else call(bridge,"complete");}
                else throw new IllegalStateException("Unknown fixture-only control: "+action);
                acknowledged = sequence; proof.setProperty("fixtureAcknowledged",String.valueOf(sequence)); persist("RUNNING");
            }
            if(seedMode)persistRawSamples();
            report = call(bridge,"report"); writeNbt("composite-server-oracle.nbt",report);
            for(String key:new String[]{"rawPrepared","rawDurableCaptured","rawPreviewMatchesServerDurable"})proof.setProperty(key,String.valueOf(bool(report,key)));
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
            if ("FAIL".equals(peer.getProperty("status"))) {proof.setProperty("failureKind",peer.getProperty("failureKind","PRODUCT_OR_ASSERTION"));throw new IllegalStateException("Client: "+peer.getProperty("failure"));}
            if ("PASS".equals(peer.getProperty("status"))) {
                check("PASS".equals(str(report,"status")),"server independent oracle complete PASS");
                if(seedMode){call(bridge,"close");writeNbt("composite-server-oracle.nbt",call(bridge,"report"));}
                proof.setProperty("clientVerdict","PASS"); persist("COMPLETE"); done=true; call(host,"func_71263_m"); return;
            }
            pulse();
        } catch(Throwable failure) {
            if(seedMode&&bridge!=null)try{call(bridge,"retainRawFailureEvidence");persistAeSamples();persistRawSamples();}catch(Throwable evidence){failure.addSuppressed(evidence);}
            fail(failure);
        }
    }

    private void observeNativeLabFailure()throws Exception {
        Class<?> laboratory=Class.forName("com.miaokatze.gtmb.testlab.TestLab");
        Object status=call(laboratory,"status");String state=str(status,"state");
        if(!"FAIL".equals(state)&&!"CANCELLED".equals(state)){observeNativeLabProgress(laboratory,status);return;}
        Object report=call(laboratory,"report");
        check("composite".equals(str(status,"runKind"))&&"composite".equals(str(report,"kind")),"terminal laboratory report belongs to this composite task");
        String detail=str(report,"detail"),stage=str(report,"compositeStage"),run=str(report,"runId");
        proof.setProperty("failureKind","FIXTURE_SETUP");proof.setProperty("nativeLabState",state);
        proof.setProperty("nativeLabDetail",detail);proof.setProperty("nativeLabRunId",run);
        proof.setProperty("nativeLabStage",stage);proof.setProperty("nativeLabCursor",String.valueOf(integer(report,"compositeCursor")));
        NativeFixtureFailure failure=new NativeFixtureFailure("Native composite task "+state+" run="+run+" stage="+stage+": "+detail);
        // An evidence-write error is secondary to the already observed native task failure.
        try{writeNbt("composite-native-lab-failure.nbt",report);}catch(Exception io){proof.setProperty("secondaryReceiptFailure",io.toString());failure.addSuppressed(io);}
        throw failure;
    }

    private void observeNativeLabProgress(Class<?> laboratory,Object status)throws Exception {
        if(seedMode||"true".equals(proof.getProperty("fixtureReady")))return;
        long now=System.nanoTime();if(now-nativeLabProgressAt<2_000_000_000L)return;nativeLabProgressAt=now;
        Object report=call(laboratory,"report"),fixture=compound(report,"fixture"),machines=list(fixture,"liveMachines");
        int formed=0,idle=0,formedIdle=0,firstUnformedIndex=-1;Object firstUnformed=null;
        for(int index=0;index<count(machines);index++){
            Object row=at(machines,index);boolean isFormed=bool(row,"formed"),isIdle=integer(row,"progress")==0&&integer(row,"maxProgress")==0;
            if(isFormed)formed++;if(isIdle)idle++;if(isFormed&&isIdle)formedIdle++;
            if(!isFormed&&firstUnformed==null){firstUnformed=row;firstUnformedIndex=index;}
        }
        proof.setProperty("nativeLabFirstUnformedIndex",String.valueOf(firstUnformedIndex));
        proof.setProperty("nativeLabFirstUnformedPosition",firstUnformed==null?"NONE":Arrays.toString(ints(firstUnformed,"position")));
        for(String key:new String[]{"timer","startUpCheck","invalid","tickDisabled","worldTileMatches","tickListContains","chunkLoaded"}){
            String value="NOT_REPORTED";
            if(firstUnformed!=null&&(Boolean)call(firstUnformed,"func_74764_b",key))
                value=key.equals("timer")||key.equals("startUpCheck")?String.valueOf(number(firstUnformed,key)):String.valueOf(bool(firstUnformed,key));
            proof.setProperty("nativeLabFirstUnformed."+key,value);
        }
        proof.setProperty("nativeLabState",str(status,"state"));proof.setProperty("nativeLabStage",str(report,"compositeStage"));
        proof.setProperty("nativeLabCursor",String.valueOf(integer(report,"compositeCursor")));proof.setProperty("nativeLabRunId",str(status,"runId"));
        proof.setProperty("nativeLabLiveMachines",String.valueOf(count(machines)));proof.setProperty("nativeLabFormedMachines",String.valueOf(formed));
        proof.setProperty("nativeLabIdleByZeroProgress",String.valueOf(idle));proof.setProperty("nativeLabFormedIdleByZeroProgress",String.valueOf(formedIdle));
        proof.setProperty("nativeLabNaturalFormedIdle",String.valueOf(bool(fixture,"naturalFormedIdle")));
        if(actor!=null){
            proof.setProperty("nativeActorY",String.valueOf(get(actor,"field_70163_u")));
            proof.setProperty("nativeActorCreative",String.valueOf(get(get(actor,"field_71075_bZ"),"field_75098_d")));
            proof.setProperty("nativeActorAlive",String.valueOf(call(actor,"func_70089_S")));
            proof.setProperty("nativeActorGameType",String.valueOf(call(call(get(actor,"field_71134_c"),"func_73081_b"),"func_77148_a")));
        }
        proof.setProperty("nativeLabProgressScope","2-second detached native status/report summary during fixture preparation only; idle count means progress=maxProgress=0; naturalFormedIdle is the native strict aggregate; no machine checks, formation writes or full-NBT disk polling");
        persist("RUNNING");
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
        if(seedMode)proof.setProperty("serverLightingAtMove."+kind,String.valueOf(CompositeWorldWitness.actorLight(world,actor)));
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
            if(!clientVerdict.isEmpty()) {if("COMPLETE".equals(peer.getProperty("status"))||"PREPARED".equals(peer.getProperty("status"))){boolean cleaned=disconnectAndReleaseClientDimension();if(cleaned)restoreClientPreferences("native-exit-cleanup");persist(clientVerdict);if(cleaned){done=true;call(mc,"func_71400_g");}}return;}
            if("FAIL".equals(peer.getProperty("status"))){proof.setProperty("failureKind",peer.getProperty("failureKind","PRODUCT_OR_ASSERTION"));throw new IllegalStateException("server failure: "+peer.getProperty("failure",""));}
            if(mc==null)mc=call(Class.forName("net.minecraft.client.Minecraft"),"func_71410_x");
            applyClientPreferences();
            Object gui=get(mc,"field_71462_r"), player=get(mc,"field_71439_g"), world=get(mc,"field_71441_e"); ticks++;
            pulse();
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
                if(seedMode&&!"true".equals(proof.getProperty("clientLightingBeforeSampled"))){
                    if(!"beforeFixtureLighting".equals(lightWaitKey)){lightWaitKey="beforeFixtureLighting";lightWaitStarted=System.nanoTime();}
                    Object before=CompositeWorldWitness.actorLight(world,player);
                    check(System.nanoTime()-lightWaitStarted<30_000_000_000L,"30-second actual client before-light loaded-chunk deadline: "+before);
                    if(!bool(compound(before,"feet"),"realChunkAvailable")||!bool(compound(before,"eye"),"realChunkAvailable"))return;
                    recordClientLight("beforeFixtureLighting",world,player);proof.setProperty("clientLightingBeforeSampled","true");persist("RUNNING");
                }
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
                if(seedMode&&!clientLightReady("source-before-"+kind(),world,player))return;
                if(!observeAreaInput(player,null)){pulse();return;}
                call(clientType("HologramClient"),"beginAreaSelection");
                Object begun=get(clientType("HologramClient"),"state");
                observeAreaInput(player,localSelection(begun==null?null:get(begun,"data")));
                proof.setProperty(kind()+"Area.beginCalls","1");
                proof.setProperty(kind()+"Area.beginLocalAvailable",proof.getProperty(kind()+"Area.localAvailable","UNAVAILABLE"));
                proof.setProperty(kind()+"Area.beginLocalContext",proof.getProperty(kind()+"Area.localContext","UNAVAILABLE"));
                wholeFlowStarted=longFlowStarted=System.nanoTime();advance(3);return;
            }
            // A standalone clipboard flow has no ordinary blueprint state to hydrate.
            if(phase>=30) {raw(peer);if(clientVerdict.isEmpty())pulse();return;}
            if(phase==14)observePrivateUpload(peer);
            Object state=get(clientType("HologramClient"),"state"), data=state==null?null:get(state,"data");
            if(state==null){if(phase>=3&&phase<=5){observeAreaInput(player,localSelection(null));pulse();}return;}
            if(phase==9||phase==12||phase==14||phase==19)checkNativeTaskOutcome(state,data);
            if(phase==3) {
                Object local=localSelection(data);observeAreaInput(player,local);pulse();if(local==null||!bool(local,"areaSelecting"))return;
                int[] at=ints(manifest,"source");point(1,at[0],at[1],at[2]);advance(4);return;
            }
            if(phase==4) {
                Object local=localSelection(data);observeAreaInput(player,local);pulse();if(local==null||ints(local,"areaPoints").length<3)return;
                int[] at=ints(manifest,"source");point(2,at[0]+39,at[1]+39,at[2]+39);advance(5);return;
            }
            if(phase==5) {
                Object local=localSelection(data);observeAreaInput(player,local);pulse();if(local==null||ints(local,"areaPoints").length!=6)return;
                call(clientType("HologramClient"),"worldAction","finishAreaSelection");advance(6);return;
            }
            if(phase==6) {
                if(!bool(data,"areaImported")||bool(data,"areaImporting")||bool(data,"capturePending")||busy())return;
                int cells=((List<?>)get(state,"cells")).size(),expected=expectedBlueprintCells(peer);
                check(cells==expected,"ordinary authoritative client hydration cells="+cells+" expected independent native non-air="+expected+" of source volume=64000");
                timing.setProperty(kind()+".importNanos",String.valueOf(System.nanoTime()-longFlowStarted));
                proof.setProperty(kind()+"BlueprintCellCount",String.valueOf(cells));proof.setProperty("actualBlueprintCellCount",String.valueOf(cells));advance(7);return;
            }
            if(phase==12) {
                if(!"true".equals(peer.getProperty(kind()+"SaveCommit"))) {
                    if(isScreen(gui)) {Object ui=call(gui,"smokeLibraryUiState");long gen=number(ui,"renderGeneration");if(bool(ui,"aeReview")&&naturalUiReady(gui,gen))confirmNativeSaveReview(gui,ui,state,data,gen);}
                    return;
                }
                verifyPair(peer,kind());timing.setProperty(kind()+".saveNanos",String.valueOf(System.nanoTime()-longFlowStarted));
                fixture("move",kind());advance(13);return;
            }
            if(phase==13) {
                if(!ack(peer))return;
                if(seedMode&&!clientLightReady(kind()+"-before-private",world,player))return;
                if(!isScreen(gui)){call(clientType("HologramClient"),"openSettings");return;}
                Object ui=call(gui,"smokeLibraryUiState");long gen=number(ui,"renderGeneration");
                if(!naturalUiReady(gui,gen)||busy()||bool(ui,"busy"))return;
                if(acknowledgeSaveGuidance(gui,gen))return;
                if(!bool(ui,"blueprintTab")||!bool(ui,"library")){if(drawnControlReady(ui,149))click(gui,ui,149,gen);return;}
                if(bool(ui,"public")){if(drawnControlReady(ui,201))click(gui,ui,201,gen);return;}
                if(!drawnControlReady(ui,141))return;
                privateLocalId=peer.getProperty(kind()+"LocalId","");
                check(!privateLocalId.isEmpty(),"actual saved client private-file identity");
                // Native successful Save selects its committed local row; wait for its real catalog refresh.
                Object library=get(clientType("HologramLibraryClient"),"INSTANCE");
                if(!privateLocalId.equals(call(library,"selected",false))||!privateLocalId.equals(str(call(library,"selectedRow",false),"id")))return;
                privatePreviousPreset=str(data,"blueprintSavedId");privateTransfer="";incomingPrivateReplies.clear();
                longFlowStarted=System.nanoTime();long previousTask=nativeTaskWatermark();click(gui,ui,141,gen);
                watchNativeTask("private",false,previousTask);
                proof.setProperty(kind()+"PrivateLoadLocalId",privateLocalId);proof.setProperty(kind()+"PrivateLoadButton","141 actual private-library MUI dispatch");
                groupIndex=0;advance(14);return;
            }
            if(phase==14) {
                if(isScreen(gui)){
                    Object ui=call(gui,"smokeLibraryUiState");long gen=number(ui,"renderGeneration");
                    if(naturalUiReady(gui,gen)){
                        if(acknowledgeSaveGuidance(gui,gen))return;
                        if(bool(ui,"discardReview")){
                            if(!drawnControlReady(ui,224))return;
                            Object library=get(get(gui,"ui"),"library");check((Integer)get(library,"deferredLibraryAction")==141,"native private-load discard retains the actual deferred141 action");
                            long previousTask=nativeTaskWatermark();click(gui,ui,224,gen);watchNativeTask("private",false,previousTask);
                            proof.setProperty(kind()+"PrivateDiscardConfirmation","actual visible enabled224 resumes native deferred141");return;
                        }
                    }
                }
                if(!bool(data,"virtualPlacement")||bool(data,"virtualLocked")||busy())return;
                Object imported=privateReply("privateImported",privateTransfer);if(imported==null)return;
                check(!privateTransfer.isEmpty()&&privateTransfer.equals(str(imported,"transfer")),"actual native privateImported transfer ACK");
                check(str(imported,"id").startsWith("private_")&&!privatePreviousPreset.equals(str(imported,"id")),"private file upload created a new owner-bound server draft");
                if(!str(imported,"id").equals(str(data,"selectedPresetId"))||!privateLocalId.equals(str(data,"libraryLocalId")))return;
                int cells=((List<?>)get(state,"cells")).size(),expected=expectedBlueprintCells(peer);
                check(cells==expected,"ordinary private artifact hydration cells="+cells+" expected independent native non-air="+expected+" of source volume=64000 before target lock");
                proof.setProperty(kind()+"PrivateBlueprintCellCount",String.valueOf(cells));
                if(!timing.containsKey(kind()+".privateLoadNanos")){
                    Object admission=privateReply("uploadReady",privateTransfer);check(admission!=null,"actual native privateBegin admission ACK");
                    Object evidence=nbt(),chunks=construct("net.minecraft.nbt.NBTTagList");
                    synchronized(incomingPrivateReplies){for(Object reply:incomingPrivateReplies)if("chunkAck".equals(str(reply,"op"))&&privateTransfer.equals(str(reply,"transfer")))call(chunks,"func_74742_a",call(reply,"func_74737_b"));}
                    call(evidence,"func_74782_a","uploadReady",call(admission,"func_74737_b"));call(evidence,"func_74782_a","chunkAcks",chunks);call(evidence,"func_74782_a","privateImported",call(imported,"func_74737_b"));
                    writeNbt("composite-"+kind()+"-private-import.nbt",evidence);proof.setProperty(kind()+"PrivateChunkAcks",String.valueOf(count(chunks)));
                    timing.setProperty(kind()+".privateLoadNanos",String.valueOf(System.nanoTime()-longFlowStarted));
                    proof.setProperty(kind()+"PrivateLoadAcknowledged","true");
                }
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
                long finished=System.nanoTime();
                timing.setProperty(kind()+".placementNanos",String.valueOf(finished-longFlowStarted)); // Historical alias includes private upload and review.
                timing.setProperty(kind()+".privatePlacementWorkflowNanos",String.valueOf(finished-longFlowStarted));
                check(constructionStarted>0,"actual confirmation timestamp precedes completed construction");
                timing.setProperty(kind()+".constructionConfirmToCompleteNanos",String.valueOf(finished-constructionStarted));
                timing.setProperty(kind()+".longFlowProductCompleteNanos",String.valueOf(finished-wholeFlowStarted));fixture("check",kind());advance(20);return;
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
            if(!naturalUiReady(gui,gen)||busy())return;
            if(phase==7) {
                if(!bool(ui,"blueprintTab")||!bool(ui,"library")){click(gui,ui,149,gen);return;}
                processingStarted=System.nanoTime();click(gui,ui,220,gen);stage=choice=0;advance(8);
            } else if(phase==8) {
                if(!bool(ui,"processingFlow"))return;
                if(!bool(ui,"flowFullReview")){click(gui,ui,296,gen);return;}
                check(integer(ui,"flowStage")==stage,"actual ordered processing stage "+stage);
                if(choice==0){if(stage==0)click(gui,ui,flow==0?232:233,gen);choice=1;}
                else if(choice==1){
                    int[] controls={235,240,flow==0?262:260,270,280,flow==0?290:291};
                    if(stage==1){
                        Object processing=compound(data,"processing");check((Boolean)call(processing,"func_74764_b","minimumLevel"),"actual native processing minimumLevel is available");
                        int minimum=integer(processing,"minimumLevel");check(minimum>=0&&minimum<=10,"native processing identity boundary in supported range");
                        int selected=Math.max(minimum,flow==0?0:6);controls[1]=240+selected;
                        proof.setProperty(kind()+"IdentityMinimumLevel",String.valueOf(minimum));proof.setProperty(kind()+"IdentitySelectedLevel",String.valueOf(selected));
                        proof.setProperty(kind()+"IdentityBoundary","native processingState.minimumLevel; self minimum, share max(minimum,6)");
                    }
                    click(gui,ui,controls[stage],gen);if(stage==3)proof.setProperty(kind()+"CoordinateDefaultSelected","auto");choice=2;
                }
                else {check(bool(ui,"flowChosen"),"explicit actual stage choice");long previousTask=stage==5?nativeTaskWatermark():0;click(gui,ui,231,gen);stage++;choice=0;if(stage==6){watchNativeTask("processing",true,previousTask);advance(9);}}
            } else if(phase==9) {
                if(!bool(data,"blueprintProcessingComplete")||bool(ui,"processingFlow"))return;
                Object appliedPolicy=compound(compound(data,"processing"),"policy");
                int appliedLevel=integer(appliedPolicy,"deidentificationLevel");check(String.valueOf(appliedLevel).equals(proof.getProperty(kind()+"IdentitySelectedLevel")),"server applied the actual selected native identity level");
                check("auto".equals(str(appliedPolicy,"coordinateDefault")),"server applied native recommended coordinate policy");
                proof.setProperty(kind()+"IdentityAppliedLevel",String.valueOf(appliedLevel));proof.setProperty(kind()+"CoordinateDefaultApplied","auto");
                timing.setProperty(kind()+".processingNanos",String.valueOf(System.nanoTime()-processingStarted));
                advance(10);
            } else if(phase==10) {
                typeName(gui,"V0111 "+kind()+" "+output.getName());generation=gen;advance(11);
            } else if(phase==11) {
                longFlowStarted=System.nanoTime();long previousTask=nativeTaskWatermark();click(gui,ui,140,gen);
                watchNativeTask("save",false,previousTask);
                proof.setProperty(kind()+"SaveButton","140 actual MUI dispatch");advance(12);
            } else if(phase==17) {
                if(!bool(data,"blueprintAwaitingConfirmation"))return;
                if(drawnControlReady(ui,55)){proof.setProperty(kind()+"DirectPlacementReview","native final55 already drawn; no redundant50 dispatch");advance(18);return;}
                if(!drawnControlReady(ui,50))return;
                click(gui,ui,50,gen);advance(18);
            } else if(phase==18) {if(!drawnControlReady(ui,55))return;long previousTask=nativeTaskWatermark();constructionStarted=System.nanoTime();click(gui,ui,55,gen);watchNativeTask("placement",true,previousTask);advance(19);}
            pulse();
        } catch(Throwable failure) {fail(failure);}
    }

    private void raw(Properties peer)throws Exception {
        if(phase==30){
            if(!ack(peer))return;
            if(!rawPrepareSent){fixture("rawPrepare","raw");rawPrepareSent=true;return;}
            check("true".equals(peer.getProperty("rawPrepared")),"independent current native source graph gate acknowledged before product CUT timer");
            if(seedMode&&!clientLightReady("raw-before-cut",get(mc,"field_71441_e"),get(mc,"field_71439_g")))return;
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
            proof.setProperty("rawCellCount","64000");timing.setProperty("raw.cutNanos",String.valueOf(System.nanoTime()-longFlowStarted));
            writeNbt("composite-raw-cut-ack.nbt",reply);fixture("rawCapture","raw");proof.setProperty("rawTimingProfile","precut graph excluded from product timer; full post-CUT durable evidence handshake included in end-to-end long flow; individual CUT/PASTE timers remain actual request-to-ACK");advance(37);return;
        }
        if(phase==37){if(!ack(peer))return;check("true".equals(peer.getProperty("rawDurableCaptured")),"server independently captured committed owner-bound disk entry before preview/paste");timing.setProperty("raw.durableWitnessHandshakeNanos",String.valueOf(System.nanoTime()-phaseStarted));rawPreviewStarted=System.nanoTime();advance(35);return;}
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
                fixture("rawPreviewVerify","raw");
                proof.setProperty("raw.previewDecodedCells","64000");
                rawRenderStarted=System.nanoTime();timing.setProperty("raw.previewHydrationNanos",String.valueOf(rawRenderStarted-rawPreviewStarted));
            }
            Object inspection=call(clientType("HologramCutRawPreview"),"inspection");
            check(integer(inspection,"cells")==64000,"production raw renderer keeps all 64000 cells");
            if(integer(inspection,"failureCount")!=0||!str(inspection,"ghostFailure").isEmpty()||!str(inspection,"ghostRenderFailure").isEmpty())writeNbt("composite-raw-preview-render.nbt",inspection);
            check(integer(inspection,"failureCount")==0,"native raw renderer failures: "+inspection);
            check(str(inspection,"ghostFailure").isEmpty()&&str(inspection,"ghostRenderFailure").isEmpty(),"native ghost render failure: "+inspection);
            if(rawNaturalFrames<8||integer(inspection,"pendingVisibleCells")!=0)return;
            if(!ack(peer))return;
            check("true".equals(peer.getProperty("rawPreviewMatchesServerDurable")),"server verified the entire client preview against independent durable disk payload before paste");
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
        lightingWorldBefore();
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
        lightingWorldAfter();
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

    private void recordClientLight(String label,Object world,Object player)throws Exception {
        Object sample=CompositeWorldWitness.actorLight(world,player);
        proof.setProperty("clientLighting."+label,String.valueOf(sample));
        // Read the actual CPU array uploaded by vanilla updateLightmap; no texture/gamma mutation.
        try{
            int[] colors=(int[])get(get(mc,"field_71460_t"),"field_78504_Q");Object feet=compound(sample,"feet");
            int index=integer(feet,"sky")*16+integer(feet,"block");
            proof.setProperty("clientLightmap."+label,"index="+index+",argb="+Integer.toHexString(colors[index])+",corners="+Integer.toHexString(colors[0])+","+Integer.toHexString(colors[15])+","+Integer.toHexString(colors[240])+","+Integer.toHexString(colors[255]));
            proof.setProperty("clientGamma."+label,String.valueOf(get(get(mc,"field_71474_y"),"field_74333_Y")));
            proof.setProperty("clientNightVision."+label,String.valueOf(call(player,"func_82165_m",16)));
        }catch(Exception unavailable){proof.setProperty("clientLightmap."+label,"UNAVAILABLE: "+unavailable);}
    }

    private boolean clientLightReady(String label,Object world,Object player)throws Exception {
        if(!label.equals(lightWaitKey)){lightWaitKey=label;lightWaitStarted=System.nanoTime();recordClientLight(label+"-arrival",world,player);}
        Object sample=CompositeWorldWitness.actorLight(world,player);
        boolean lit=integer(compound(sample,"feet"),"block")>0&&integer(compound(sample,"eye"),"block")>0;
        check(System.nanoTime()-lightWaitStarted<30_000_000_000L,"30-second native client block-light/WorldLast observation deadline at "+label+": "+sample+" baselineFrames="+baselineLightingFrames);
        if(!lit||baselineLightingFrames<8)return false;
        recordClientLight(label+"-ready",world,player);proof.setProperty("clientLightingReady."+label,"true");return true;
    }

    /** Eight bounded natural whole-event samples before business and during actual raw preview. */
    private void lightingWorldBefore(){
        lightingGlWitness=null;
        if(!client||done||!seedMode||mc==null)return;
        try{
            if(get(mc,"field_71441_e")==null||get(mc,"field_71439_g")==null||get(mc,"field_71462_r")!=null)return;
            boolean preview=phase==36;
            if(preview?previewLightingFrames>=8:(!(phase==1||phase==2||phase==30)||baselineLightingFrames>=8))return;
            Object cut=get(clientType("HologramCutClient"),"INSTANCE");
            if(preview?get(cut,"rawPreview")==null:get(cut,"rawPreview")!=null)return;
            if(!preview){Object light=CompositeWorldWitness.actorLight(get(mc,"field_71441_e"),get(mc,"field_71439_g"));if(integer(compound(light,"feet"),"block")<=0||integer(compound(light,"eye"),"block")<=0)return;}
            lightingGlLabel=preview?"rawPreview":"beforeBusiness";
            lightingGlBefore=namedRenderState();
            lightingGlWitness=construct("com.miaokatze.gtmb.client.hologram.HologramCutRawPreviewScope$StateWitness");
        }catch(Throwable unavailable){proof.setProperty("lightingGlObservation","UNAVAILABLE: "+unavailable);}
    }
    private void lightingWorldAfter(){
        if(lightingGlWitness==null)return;
        try{
            String after=namedRenderState();boolean equal=(Boolean)call(lightingGlWitness,"matches");
            int index=lightingGlLabel.equals("rawPreview")?++previewLightingFrames:++baselineLightingFrames;
            proof.setProperty("lightingWorldLast."+lightingGlLabel+"."+index+".before",lightingGlBefore);
            proof.setProperty("lightingWorldLast."+lightingGlLabel+"."+index+".after",after);
            proof.setProperty("lightingWorldLast."+lightingGlLabel+"."+index+".wholeEventStateMatches",String.valueOf(equal));
            proof.setProperty("lightingWorldLast."+lightingGlLabel+".frames",String.valueOf(index));
            proof.setProperty("lightingWorldLast.scope","natural HIGHEST-to-LOWEST whole Forge WorldLast; other mods included; named read-only GL and native CTM state witness; no pixel or sole-renderer PASS inference");
        }catch(Throwable unavailable){proof.setProperty("lightingGlObservation","UNAVAILABLE: "+unavailable);}
        finally{lightingGlWitness=null;}
    }
    private String namedRenderState()throws Exception {
        Class<?> gl=Class.forName("org.lwjgl.opengl.GL11"),helper=Class.forName("net.minecraft.client.renderer.OpenGlHelper");
        StringBuilder out=new StringBuilder("brightnessX=").append(get(helper,"lastBrightnessX")).append(",brightnessY=").append(get(helper,"lastBrightnessY"));
        int[] names={0x84e0,0x84e1,0x0ba0,0x0b72,0x0b74,0x0be1,0x0be0,0x8069};
        String[] labels={"activeTexture","clientTexture","matrixMode","depthWrite","depthFunc","blendSrc","blendDst","activeUnitTexture2D"};
        for(int i=0;i<names.length;i++)out.append(',').append(labels[i]).append('=').append(call(gl,"glGetInteger",names[i]));
        for(int name:new int[]{0x0b50,0x0b60,0x0be2,0x0bc0,0x0b71,0x0c11})out.append(",enabled:").append(Integer.toHexString(name)).append('=').append(call(gl,"glIsEnabled",name));
        java.nio.FloatBuffer values=java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        // Reflection is not rewritten by lwjgl3ify's LWJGL2 bytecode compatibility transformer.
        try{call(gl,"glGetFloatv",0x0b00,values);proof.setProperty("lightingGlColorGetter","GL11.glGetFloatv(int,FloatBuffer)");}
        catch(NoSuchMethodException lwjgl2){call(gl,"glGetFloat",0x0b00,values);proof.setProperty("lightingGlColorGetter","GL11.glGetFloat(int,FloatBuffer)");}
        out.append(",color=");for(int i=0;i<4;i++)out.append(values.get(i)).append('/');
        return out.toString();
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

    private void observePrivateUpload(Properties peer)throws Exception {
        if(!privateTransfer.isEmpty())return;
        Object library=get(clientType("HologramLibraryClient"),"INSTANCE"),outgoing=get(library,"outgoing");
        if(outgoing==null||!"private".equals(get(library,"outgoingOp")))return;
        check(privateLocalId.equals(get(library,"outgoingLocal")),"native private upload reads the actual saved local file");
        byte[] bytes=(byte[])outgoing;
        Object artifact=call(product("HologramBlueprintArtifact"),"decode",bytes);
        check(peer.getProperty(kind()+"JsonSha256","").equals(hash((byte[])get(artifact,"json"))),"native private upload contains the committed JSON bytes");
        check(peer.getProperty(kind()+"PngSha256","").equals(hash((byte[])get(artifact,"png"))),"native private upload contains the committed PNG bytes");
        privateTransfer=(String)get(library,"outgoingId");check(!privateTransfer.isEmpty(),"real native private transfer identity");
        proof.setProperty(kind()+"PrivateTransferId",privateTransfer);proof.setProperty(kind()+"PrivateArtifactBytes",String.valueOf(bytes.length));
        proof.setProperty(kind()+"PrivateArtifactSha256",hash(bytes));
        proof.setProperty(kind()+"PrivateLoadScope","actual private-library141 action; native store.artifact reads JSON+PNG pair, privateBegin/chunks and owner-bound privateImported ACK; no cached-draft activation");
    }

    private Object privateReply(String operation,String transfer)throws Exception {
        if(transfer.isEmpty())return null;
        synchronized(incomingPrivateReplies){for(Object reply:incomingPrivateReplies)if(operation.equals(str(reply,"op"))&&transfer.equals(str(reply,"transfer")))return reply;}
        return null;
    }

    private boolean observeAreaInput(Object player,Object local)throws Exception {
        Object held=call(player,"func_70694_bm");int slot=(Integer)get(get(player,"field_71071_by"),"field_70461_c");
        boolean projector=(Boolean)call(Class.forName("com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector"),"isProjector",held),background=busy();
        String prefix=kind()+"Area.";
        proof.setProperty(prefix+"heldSlot",String.valueOf(slot));proof.setProperty(prefix+"heldProjector",String.valueOf(projector));
        proof.setProperty(prefix+"backgroundBusy",String.valueOf(background));proof.setProperty(prefix+"dimension",String.valueOf(get(player,"field_71093_bK")));
        proof.setProperty(prefix+"worldIdentity",String.valueOf(System.identityHashCode(get(mc,"field_71441_e"))));
        proof.setProperty(prefix+"heldItem",held==null?"NONE":call(held,"func_77973_b").getClass().getName());
        proof.setProperty(prefix+"heldDamage",held==null?"NONE":String.valueOf(call(held,"func_77960_j")));
        proof.setProperty(prefix+"heldToolId",held!=null&&(Boolean)call(held,"func_77942_o")?str(call(held,"func_77978_p"),"gtitHologramToolId"):"");
        proof.setProperty(prefix+"localAvailable",String.valueOf(local!=null));
        proof.setProperty(prefix+"localSelecting",local==null?"UNAVAILABLE":String.valueOf(bool(local,"areaSelecting")));
        Object cut=get(clientType("HologramCutClient"),"INSTANCE");
        proof.setProperty(prefix+"cutMode",String.valueOf(call(cut,"cutMode")));proof.setProperty(prefix+"cutActivity",String.valueOf(call(cut,"active")));
        try{proof.setProperty(prefix+"localContext",String.valueOf(call(clientType("HologramClient"),"localAreaContext")));}
        catch(NoSuchMethodException historical){proof.setProperty(prefix+"localContext","API_UNAVAILABLE");}
        return slot==0&&projector&&!background;
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
        admittedClientConnection=call(call(mc,"func_147114_u"),"func_147298_b");
        Class<?> dimensions=Class.forName("net.minecraftforge.common.DimensionManager");
        check((Boolean)call(dimensions,"isDimensionRegistered",clientDimensionId),"actual connected client dimension mapping exists");
        check((Integer)call(dimensions,"getProviderType",clientDimensionId)==clientProviderId,"actual connected client dimension uses exact native provider");
        check(((java.util.Map<?,?>)get(dimensions,"providers")).get(clientProviderId)==Class.forName("com.miaokatze.gtmb.testlab.TestLabWorldProvider"),"actual connected client provider class is native TestLabWorldProvider");
        if(productClientMappingLifecycle)check(bool(call(Class.forName("com.miaokatze.gtmb.testlab.TestLab"),"status"),"clientDimensionMapped"),"product connection event actually owns the native client mapping");
        proof.setProperty("actualClientDimensionMappingVerified","true");
    }

    private boolean disconnectAndReleaseClientDimension()throws Exception {
        if(!client)return true;
        if(!clientDisconnectInitiated){
            clientDisconnectInitiated=true;clientDimensionCleanupStarted=System.nanoTime();
            if(mc!=null&&get(mc,"field_71441_e")!=null){
                // Invoke the real menu branch: quitting packet, world unload, then MainMenu in one native action.
                // Loading null with no replacement screen leaves Freecam's ordinary mouse-look path with no player.
                Object menu=construct("net.minecraft.client.gui.GuiIngameMenu");call(mc,"func_147108_a",menu);
                check(get(mc,"field_71462_r")==menu,"actual native pause menu opened for disconnect");
                Object disconnect=null;
                for(Object button:(List<?>)get(menu,"field_146292_n"))if((Integer)get(button,"field_146127_k")==1){disconnect=button;break;}
                check(disconnect!=null,"actual native disconnect button exists");call(menu,"func_146284_a",disconnect);
                proof.setProperty("clientDisconnectAction","native GuiIngameMenu button1: quitting packet, world unload, main menu");
            }else{
                if(mc!=null&&get(mc,"field_71462_r")==null)call(mc,"func_147108_a",construct("net.minecraft.client.gui.GuiMainMenu"));
                proof.setProperty("clientDisconnectAction","world already unloaded by native connection; preserve native screen or open main menu if absent");
            }
            proof.setProperty("clientDisconnectWait","return to ordinary client ticks before observing native mapping cleanup; no synchronous wait or lifecycle callback injection");
            return false;
        }
        if(productClientMappingLifecycle) {
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

    @SubscribeEvent
    public void nativeClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event){
        if(!client||done)return;
        proof.setProperty("nativeClientDisconnectEventThread",Thread.currentThread().getName());
        proof.setProperty("nativeClientDisconnectEventMatchesAdmittedConnection",String.valueOf(event.manager==admittedClientConnection));
        if(admittedClientConnection!=null&&event.manager!=admittedClientConnection)return;
        // Runner abort also closes the real socket; restore on the ordinary client queue even without a PASS path.
        try{call(mc,"func_152344_a",(Runnable)()->{
            try{restoreClientPreferences("native-disconnection-event");persist(proof.getProperty("status","RUNNING"));}
            catch(Exception failure){proof.setProperty("clientPreferencesRestoreFailure",failure.toString());failure.printStackTrace();}
        });}catch(Exception failure){proof.setProperty("clientPreferencesRestoreFailure",failure.toString());failure.printStackTrace();}
    }

    private void applyClientPreferences()throws Exception {
        if(previousPauseOnLostFocus!=null)return;
        Object settings=get(mc,"field_71474_y");previousPauseOnLostFocus=(Boolean)get(settings,"field_82881_y");
        proof.setProperty("preferencesRestored","false");
        set(settings,"field_82881_y",false);check(!(Boolean)get(settings,"field_82881_y"),"native pauseOnLostFocus environment setting applied");
        proof.setProperty("pauseOnLostFocusPrevious",String.valueOf(previousPauseOnLostFocus));proof.setProperty("pauseOnLostFocusApplied","false");
        proof.setProperty("pauseOnLostFocusScope","uniform native GameSettings environment before measurement; avoid background-window auto-pause cancelling the probe; no change to product Escape behavior and no begin retries");
    }

    private void restoreClientPreferences(String reason)throws Exception {
        if(previousPauseOnLostFocus==null||clientPreferencesRestored)return;
        Object settings=get(mc,"field_71474_y");set(settings,"field_82881_y",previousPauseOnLostFocus);
        check(previousPauseOnLostFocus.equals(get(settings,"field_82881_y")),"original native pauseOnLostFocus preference restored");
        clientPreferencesRestored=true;proof.setProperty("pauseOnLostFocusRestored",String.valueOf(previousPauseOnLostFocus));
        proof.setProperty("clientPreferencesRestoreReason",reason);proof.setProperty("preferencesRestored","true");
    }
    private void point(int point,int x,int y,int z)throws Exception {try{check((Boolean)call(clientType("HologramClient"),"localAreaPoint",point,x,y,z,"replace"),"actual local area point");}catch(NoSuchMethodException old){Object state=get(clientType("HologramClient"),"state"),request=worldRequest(state,"areaPoint");putInt(request,"point",point);putInt(request,"x",x);putInt(request,"y",y);putInt(request,"z",z);call(product("HologramNetwork"),"sendAction",request);}}
    private void fixture(String action,String kind)throws Exception {Properties request=new Properties();request.setProperty("sequence",String.valueOf(++requestSequence));request.setProperty("action",action);request.setProperty("kind",kind);store("fixture-request.properties",request);fixtureRequest=action;fixtureKind=kind;}
    private boolean ack(Properties peer){return Integer.parseInt(peer.getProperty("fixtureAcknowledged","0"))==requestSequence;}
    private String kind(){return scenario.equals("raw")?"raw":flow==0?"self":"share";}
    private void uiSend(Object gui,String op,Object request)throws Exception {check(isScreen(gui),"real live MUI session required");call(get(get(gui,"ui"),"session"),"send",op,request,true);}
    private void networkOp(Object state,String op,Object request)throws Exception {putText(request,"op",op);putText(request,"session",String.valueOf(get(state,"session")));call(request,"func_74772_a","planRevision",number(get(state,"data"),"planRevision"));call(product("HologramNetwork"),"sendAction",request);}
    private Object worldRequest(Object state,String action)throws Exception {Object request=nbt();putText(request,"action",action);putText(request,"session",String.valueOf(get(state,"session")));call(request,"func_74772_a","planRevision",number(get(state,"data"),"planRevision"));return request;}

    private int expectedBlueprintCells(Properties peer){
        int volume=Integer.parseInt(peer.getProperty("actualSourceVolume","-1")),nonAir=Integer.parseInt(peer.getProperty("actualSourceNonAirBlocks","-1")),expected=Integer.parseInt(peer.getProperty("expectedBlueprintCells","-1"));
        check(volume==64000&&nonAir>0&&nonAir<=volume&&expected==nonAir,"ordinary blueprint count must come from the complete independent native World baseline, not product cells");
        proof.setProperty("expectedSourceVolume",String.valueOf(volume));proof.setProperty("expectedBlueprintCells",String.valueOf(expected));
        return expected;
    }

    private void verifyPair(Properties peer,String kind)throws Exception {
        String id=peer.getProperty(kind+"LocalId","");check(id.matches("local_[a-fA-F0-9-]{36}"),"real owner-committed local file ID");
        File game=(File)get(mc,"field_71412_D");Path directory=new File(game,"MiaoBlueprints").getCanonicalFile().toPath();Path json=directory.resolve(id+".json"),png=directory.resolve(id+".png");
        check(Files.isRegularFile(json)&&Files.isRegularFile(png),"actual JSON+PNG on client disk");
        int jsonLimit=(Integer)get(product("HologramBlueprintSharing"),"MAX_BYTES");check(Files.size(json)<=jsonLimit,"actual committed JSON is within this product version's native byte limit");
        byte[] jsonBytes=Files.readAllBytes(json),pngBytes=Files.readAllBytes(png);
        check(hash(jsonBytes).equals(peer.getProperty(kind+"JsonSha256")),"independent JSON SHA matches exact production owner commit");
        check(hash(pngBytes).equals(peer.getProperty(kind+"PngSha256")),"independent PNG SHA matches exact production owner commit");
        java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(png.toFile());check(image!=null&&image.getWidth()==640&&image.getHeight()==640,"actual native preview image dimensions");
        Object artifact=call(product("HologramBlueprintArtifact"),"readPair",json);
        check(hash(jsonBytes).equals(get(artifact,"jsonSha256")),"paired PNG source-JSON binding validated");
        Object preset=call(product("HologramBlueprintSharing"),"decode",new String(jsonBytes,java.nio.charset.StandardCharsets.UTF_8));
        int cells=((List<?>)get(preset,"cells")).size(),expected=expectedBlueprintCells(peer);
        check(cells==expected,"ordinary committed disk JSON cells="+cells+" expected independent native non-air="+expected+" of source volume=64000");
        proof.setProperty(kind+"SavedBlueprintCellCount",String.valueOf(cells));
        Files.copy(json,file(kind+"-actual-saved.json").toPath());Files.copy(png,file(kind+"-actual-saved.png").toPath());
        proof.setProperty(kind+"ActualPair","true");proof.setProperty(kind+"JsonLimit",String.valueOf(jsonLimit));proof.setProperty(kind+"JsonBytes",String.valueOf(jsonBytes.length));proof.setProperty(kind+"PngBytes",String.valueOf(pngBytes.length));proof.setProperty(kind+"JsonSha256",hash(jsonBytes));proof.setProperty(kind+"PngSha256",hash(pngBytes));
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
        // The existing post-delivery observer leaves the actual library receiver and its request/ACK handling intact.
        originalLibraryProbe=(Consumer<Object>)get(product("HologramNetwork"),"libraryProbeReceiver");
        member(product("HologramNetwork"),"libraryProbeReceiver").set(null,(Consumer<Object>)value->{
            try{if(Arrays.asList("uploadReady","chunkAck","privateImported").contains(str(value,"op")))incomingPrivateReplies.add(value);}catch(Exception error){throw new IllegalStateException("Cannot observe native private-library ACK",error);}
            if(originalLibraryProbe!=null)originalLibraryProbe.accept(value);
        });libraryProbeInstalled=true;
    }
    private void restoreCutWitness()throws Exception {
        if(client&&cutWitnessInstalled){member(product("HologramNetwork"),"cutReceiver").set(null,originalCut);originalCut=null;cutWitnessInstalled=false;}
        if(client&&progressWitnessInstalled){member(product("HologramNetwork"),"progressReceiver").set(null,originalProgress);originalProgress=null;progressWitnessInstalled=false;}
        if(client&&libraryProbeInstalled){member(product("HologramNetwork"),"libraryProbeReceiver").set(null,originalLibraryProbe);originalLibraryProbe=null;libraryProbeInstalled=false;}
    }
    private Object cutReply(String request)throws Exception {synchronized(incomingCuts){for(Object reply:incomingCuts)if(request.equals(str(reply,"requestId")))return reply;}return null;}
    private static boolean isScreen(Object gui){return gui!=null&&gui.getClass().getName().equals("com.miaokatze.gtmb.client.hologram.HologramScreen");}
    private boolean naturalUiReady(Object gui,long gen){
        if(generationScreen!=gui){generationScreen=gui;generation=-1;}
        return gen>0&&gen>generation;
    }
    private Boolean nativeSaveGuidanceOpen(Object gui)throws Exception {
        try{return (Boolean)call(get(get(gui,"ui"),"library"),"saveGuidanceOpen");}
        catch(NoSuchMethodException historical){return null;}
    }
    private boolean acknowledgeSaveGuidance(Object gui,long gen)throws Exception {
        Boolean open=nativeSaveGuidanceOpen(gui);
        if(!Boolean.TRUE.equals(open)){
            if(Boolean.FALSE.equals(open)&&guidanceAwaitingGui==gui){proof.setProperty(kind()+"SaveGuidanceAcknowledged","native modal closed on a later natural frame");guidanceAwaitingGui=null;}
            return false;
        }
        if(guidanceAwaitingGui!=gui){
            call(clientType("HologramMuiProbeInput"),"key",gui,'\r',28);guidanceAwaitingGui=gui;generationScreen=gui;generation=gen;
            proof.setProperty(kind()+"SaveGuidanceInput","native MUI Return28; original guidance consumeKey acknowledges without suppression");
        }
        return true;
    }
    private Object drawnControl(Object ui,int id)throws Exception {
        Object controls=list(ui,"drawnControls");for(int i=0;i<count(controls);i++){Object control=at(controls,i);if(integer(control,"id")==id)return control;}return null;
    }
    private boolean drawnControlReady(Object ui,int id)throws Exception {Object control=drawnControl(ui,id);return control!=null&&bool(control,"enabled");}
    private Object nativeAeReview(Object gui)throws Exception {return call(clientType("HologramUiDiagnostics"),"readDiagnosticField",gui,"aeReview");}
    private long nativePendingSequence(Object gui)throws Exception {return (Long)call(clientType("HologramUiDiagnostics"),"readDiagnosticField",gui,"pendingSequence");}
    private Object nativeAeReviewObservation(Object gui)throws Exception {
        Object observed=nbt(),model=nativeAeReview(gui);putText(observed,"source","read-only actual native AE review model fields; no diagnostic smoke getter or replacement model");
        call(observed,"func_74772_a","pendingSequence",nativePendingSequence(gui));if(model==null)return observed;
        for(String key:new String[]{"session","transactionId"})putText(observed,key,String.valueOf(get(model,key)));
        call(observed,"func_74772_a","revision",get(model,"revision"));
        for(String key:new String[]{"valid","review","canConfirm"})call(observed,"func_74757_a",key,get(model,key));
        for(String key:new String[]{"blockerCount","warningCount"})putInt(observed,key,(Integer)get(model,key));return observed;
    }
    private void confirmNativeSaveReview(Object gui,Object ui,Object state,Object data,long gen)throws Exception {
        if(drawnControl(ui,181)==null)return;
        Object model=nativeAeReview(gui);if(model==null||nativePendingSequence(gui)>=0||!(Boolean)get(model,"review"))return;
        Object review=compound(data,"aeSaveReview");String transaction=str(review,"transactionId");
        if(transaction.isEmpty()||!transaction.equals(get(model,"transactionId"))||!String.valueOf(get(state,"session")).equals(get(model,"session"))||number(data,"planRevision")!=(Long)get(model,"revision"))return;
        boolean valid=(Boolean)get(model,"valid"),canConfirm=(Boolean)get(model,"canConfirm");int blockers=(Integer)get(model,"blockerCount");
        if(!valid||blockers>0||!canConfirm){
            IllegalStateException failure=new IllegalStateException("Native AE Save review cannot confirm: valid="+valid+" blockers="+blockers+" canConfirm="+canConfirm);
            try{exportBlockedControl(ui,181);}catch(Exception diagnostic){proof.setProperty("blockedControlReportFailure",diagnostic.toString());failure.addSuppressed(diagnostic);}throw failure;
        }
        if(!(Boolean)call(model,"matches",String.valueOf(get(state,"session")),number(data,"planRevision")))return;
        if(drawnControlReady(ui,181))click(gui,ui,181,gen);
    }
    private long nativeTaskWatermark()throws Exception {
        Object task=call(clientType("HologramBackgroundTasks"),"actualTaskWitness");return Math.max(number(task,"token"),number(task,"completedToken"));
    }
    private void watchNativeTask(String operation,boolean command,long previousTask)throws Exception {
        Class<?> tasks=clientType("HologramBackgroundTasks");Object state=get(clientType("HologramClient"),"state");
        awaitedTaskToken=0;long token=(Long)call(tasks,"token");String prefix=kind()+"."+operation+".";
        proof.setProperty(prefix+"previousTaskWatermark",String.valueOf(previousTask));
        if(token<=previousTask){proof.setProperty(prefix+"nativeTaskEvidence","NO_NEW_TOKEN; original deadline retained");return;}
        awaitedTaskKind=operation;awaitedTaskSession=String.valueOf(get(state,"session"));
        awaitedTaskSequence=command?(Long)call(tasks,"commandSequence",awaitedTaskSession):-1;
        if(command&&awaitedTaskSequence<0){proof.setProperty(prefix+"nativeTaskEvidence","NO_COMMAND_SEQUENCE; original deadline retained");return;}
        awaitedTaskToken=token;proof.setProperty(prefix+"nativeTaskEvidence","NEW_NATIVE_TOKEN");
        proof.setProperty(kind()+"."+operation+".nativeTaskToken",String.valueOf(awaitedTaskToken));
        proof.setProperty(kind()+"."+operation+".nativeTaskSession",awaitedTaskSession);proof.setProperty(kind()+"."+operation+".nativeTaskSequence",String.valueOf(awaitedTaskSequence));
    }
    private void checkNativeTaskOutcome(Object state,Object data)throws Exception {
        if(awaitedTaskToken<=0)return;
        Class<?> tasks=clientType("HologramBackgroundTasks");Object task=call(tasks,"actualTaskWitness");
        if(bool(task,"busy")||number(task,"completedToken")!=awaitedTaskToken||(Boolean)call(tasks,"succeeded"))return;
        String detail=String.valueOf(call(tasks,"status"));
        IllegalStateException failure=new IllegalStateException("Native "+awaitedTaskKind+" task failed token="+awaitedTaskToken+" session="+awaitedTaskSession+" sequence="+awaitedTaskSequence+": "+detail);
        proof.setProperty("nativeTerminalFailureOperation",awaitedTaskKind);proof.setProperty("nativeTerminalFailureToken",String.valueOf(awaitedTaskToken));
        proof.setProperty("nativeTerminalFailureStatus",detail.substring(0,Math.min(detail.length(),1024)));
        try{
            Object report=nbt();putText(report,"operation",awaitedTaskKind);putText(report,"requestSession",awaitedTaskSession);putText(report,"actualSession",String.valueOf(get(state,"session")));
            call(report,"func_74772_a","requestToken",awaitedTaskToken);call(report,"func_74772_a","requestSequence",awaitedTaskSequence);putInt(report,"phase",phase);putText(report,"nativeTaskStatus",detail);
            call(report,"func_74782_a","nativeTaskWitness",call(task,"func_74737_b"));
            for(String key:new String[]{"status","uiSequence","planRevision","processingPresetOperation","processingPresetOperationSuccess","blueprintProcessingComplete","processing","resourceReview","resourceBlockers","rootReport","blueprintOperation","blueprintOperationSuccess","blueprintSavePending","job","jobPartialPlacement","jobMissingCells","jobProtectedCells","jobUnsupportedCells","jobSkippedCells","progress"})
                if((Boolean)call(data,"func_74764_b",key))call(report,"func_74782_a",key,call(call(data,"func_74781_a",key),"func_74737_b"));
            Object failedCells=construct("net.minecraft.nbt.NBTTagList");int failedCount=0;
            for(Object cell:(List<?>)get(state,"cells")){
                if(!Arrays.asList("unsupported","protected","blocked","missing").contains(String.valueOf(get(cell,"status"))))continue;
                failedCount++;if(count(failedCells)>=128)continue;Object row=nbt();
                for(String key:new String[]{"index","x","y","z","dx","dy","dz","meta","wantMeta"})putInt(row,key,((Number)get(cell,key)).intValue());
                for(String key:new String[]{"id","wantId","status","operation","sourceClass","sourceWarning"}){String value=String.valueOf(get(cell,key));putText(row,key,value.substring(0,Math.min(value.length(),512)));}
                call(failedCells,"func_74742_a",row);
            }
            call(report,"func_74782_a","failedClientCells",failedCells);putInt(report,"failedClientCellCount",failedCount);
            putText(report,"failedClientCellScope","actual published client cells at terminal failure; at most128 scalar samples, no native tile or item NBT; not an independent server-world oracle");
            Object gui=get(mc,"field_71462_r");if(isScreen(gui))call(report,"func_74782_a","smokeUI",call(gui,"smokeLibraryUiState"));
            String name="composite-"+kind()+"-terminal-failure.nbt";writeNbt(name,report);proof.setProperty("nativeTerminalFailureReport",name);
        }catch(Exception diagnostic){proof.setProperty("nativeTerminalFailureReportError",diagnostic.toString());failure.addSuppressed(diagnostic);}
        throw failure;
    }
    private void click(Object gui,Object ui,int id,long gen)throws Exception {
        Object controls=list(ui,"drawnControls");boolean enabled=false;for(int i=0;i<count(controls);i++){Object c=at(controls,i);if(integer(c,"id")==id){enabled=bool(c,"enabled");break;}}
        if(!enabled){
            IllegalStateException failure=new IllegalStateException("actual visible enabled MUI control "+id);
            try{exportBlockedControl(ui,id);}catch(Exception diagnostic){proof.setProperty("blockedControlReportFailure",diagnostic.toString());failure.addSuppressed(diagnostic);}
            throw failure;
        }
        call(gui,"smokeControlPoint",id);call(clientType("HologramMuiProbeInput"),"control",gui,id);generationScreen=gui;generation=gen;proof.setProperty("lastControl",String.valueOf(id));
    }
    private void exportBlockedControl(Object ui,int id)throws Exception {
        Object state=get(clientType("HologramClient"),"state"),data=state==null?null:get(state,"data"),report=nbt();
        putInt(report,"control",id);putInt(report,"phase",phase);putInt(report,"stage",stage);putText(report,"scenario",kind());
        call(report,"func_74782_a","smokeUI",call(ui,"func_74737_b"));
        if(phase==12&&isScreen(get(mc,"field_71462_r")))call(report,"func_74782_a","nativeAeReviewState",nativeAeReviewObservation(get(mc,"field_71462_r")));
        if(data!=null){
            for(String key:new String[]{"processing","resourceReview","resourceBlockers","rootReport","aeSaveReview"}){
                boolean present=(Boolean)call(data,"func_74764_b",key);putInt(report,key+"Present",present?1:0);
                if(present)call(report,"func_74782_a",key,call(call(data,"func_74781_a",key),"func_74737_b"));
            }
            Object processing=compound(data,"processing"),resourceHost=data;
            if(!(Boolean)call(data,"func_74764_b","resourceReview")&&!(Boolean)call(data,"func_74764_b","resourceBlockers"))resourceHost=processing;
            String resourceKey=(Boolean)call(resourceHost,"func_74764_b","resourceReview")?"resourceReview":"resourceBlockers";
            Object rows=list(resourceHost,resourceKey);proof.setProperty("blockedControlResourceRows",String.valueOf(count(rows)));
            int sampled=0;for(int i=0;i<count(rows)&&sampled<16;i++){
                Object row=at(rows,i);if(bool(row,"canPreserve"))continue;
                String prefix="blockedResource."+(sampled++)+".";proof.setProperty(prefix+"index",String.valueOf(i));
                for(String key:new String[]{"x","y","z"})proof.setProperty(prefix+key,String.valueOf(integer(row,key)));
                for(String key:new String[]{"scope","path","rootKey","message"}){String value=str(row,key);proof.setProperty(prefix+key,value.substring(0,Math.min(value.length(),512)));}
                proof.setProperty(prefix+"canPreserve",String.valueOf(bool(row,"canPreserve")));proof.setProperty(prefix+"canMigrate",String.valueOf(bool(row,"canMigrate")));
            }
            proof.setProperty("blockedControlResourceSamples",String.valueOf(sampled));
        }
        String name="composite-"+kind()+"-blocked-control.nbt";writeNbt(name,report);proof.setProperty("blockedControlReport",name);proof.setProperty("blockedControl",String.valueOf(id));
    }
    private void typeName(Object gui,String name)throws Exception {Class<?> diag=clientType("HologramUiDiagnostics");Object field=call(diag,"readDiagnosticField",gui,"presetNameField");float scale=(Float)call(diag,"readDiagnosticField",gui,"scale");int x=(Integer)call(diag,"readDiagnosticField",gui,"originX")+Math.round(((Integer)get(field,"xPosition")+4)*scale),y=(Integer)call(diag,"readDiagnosticField",gui,"originY")+Math.round(((Integer)get(field,"yPosition")+7)*scale);call(clientType("HologramMuiProbeInput"),"click",gui,x,y,0);check((Boolean)call(field,"isFocused"),"native name field focus");call(clientType("HologramMuiProbeInput"),"key",gui,(char)1,30);for(char c:name.toCharArray())call(clientType("HologramMuiProbeInput"),"key",gui,c,0);check(name.equals(call(field,"getText")),"exact native keyboard name");}
    private void advance(int next)throws Exception {long now=System.nanoTime();timing.setProperty(kind()+".phase."+phase+".nanos",String.valueOf(now-phaseStarted));phase=next;phaseStarted=now;persist("RUNNING");}
    private void pulse()throws Exception {
        if(System.nanoTime()-heartbeat<2_000_000_000L)return;heartbeat=System.nanoTime();
        if(client&&mc!=null){
            Object screen=get(mc,"field_71462_r");proof.setProperty("nativeClientScreen",screen==null?"NONE":screen.getClass().getName());
            proof.setProperty("nativeBackgroundBusy",String.valueOf(busy()));
            Object actualState=get(clientType("HologramClient"),"state");proof.setProperty("nativeBlueprintDirty",actualState==null?"NO_STATE":String.valueOf(bool(get(actualState,"data"),"blueprintDirty")));
            if(actualState!=null){
                Object data=get(actualState,"data"),progress=compound(data,"progress");
                proof.setProperty("nativeJob",String.valueOf(integer(data,"job")));
                proof.setProperty("nativeProgressPhase",str(progress,"phase"));
                for(String key:new String[]{"done","total","uiSequence","planRevision","sequence"})proof.setProperty("nativeProgress."+key,String.valueOf(number(progress,key)));
            }
            if(isScreen(screen)){
                proof.setProperty("nativeDiscardReview",String.valueOf(get(get(get(screen,"ui"),"library"),"discardReview")));
                Boolean guidance=nativeSaveGuidanceOpen(screen);proof.setProperty("nativeSaveGuidanceOpen",guidance==null?"ABSENT_NATIVE_API":String.valueOf(guidance));
            }else{proof.setProperty("nativeDiscardReview","NO_WORKSPACE");proof.setProperty("nativeSaveGuidanceOpen","NO_WORKSPACE");}
            observeDisplayActive();
            proof.setProperty("nativePauseOnLostFocus",String.valueOf(get(get(mc,"field_71474_y"),"field_82881_y")));
            try{
                Class<?> type=clientType("HologramClient");proof.setProperty("nativeLocalAreaPresent",String.valueOf(get(type,"localArea")!=null));
                proof.setProperty("nativeLocalAreaWorldIdentity",String.valueOf(System.identityHashCode(get(type,"localAreaWorld"))));
                for(String key:new String[]{"localAreaAwaitingBegin","localAreaFinishing","localAreaCancelling"})proof.setProperty("native."+key,String.valueOf(get(type,key)));
            }catch(NoSuchFieldException historical){proof.setProperty("nativeLocalAreaFlags","API_UNAVAILABLE");}
        }
        persist("RUNNING");
    }
    private void observeDisplayActive(){
        if(displayActiveUnavailable)return;
        try{proof.setProperty("nativeDisplayActive",String.valueOf(call(Class.forName("org.lwjgl.opengl.Display"),"isActive")));}
        catch(Exception|LinkageError unavailable){
            Throwable cause=unavailable;while(cause instanceof java.lang.reflect.InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();
            String detail=cause.toString();displayActiveUnavailable=true;
            proof.setProperty("nativeDisplayActive","API_UNAVAILABLE");proof.setProperty("nativeDisplayActiveCause",detail.substring(0,Math.min(detail.length(),256)));
        }
    }
    private void persist(String status)throws Exception {proof.setProperty("status",status);proof.setProperty("phase",String.valueOf(phase));proof.setProperty("flow",kind());proof.setProperty("phaseElapsedNanos",String.valueOf(System.nanoTime()-phaseStarted));proof.setProperty("elapsedNanos",String.valueOf(System.nanoTime()-started));store(client?"composite-client.properties":"composite-server.properties",proof);store(client?"composite-client-timing.properties":"composite-server-timing.properties",timing);}
    private void fail(Throwable failure){
        while(failure instanceof java.lang.reflect.InvocationTargetException&&failure.getCause()!=null)failure=failure.getCause();
        failure.printStackTrace();
        try{
            if(!proof.containsKey("failureKind"))proof.setProperty("failureKind",failure instanceof ReceiptIoException?"TEST_IO":failure instanceof NativeFixtureFailure?"FIXTURE_SETUP":"PRODUCT_OR_ASSERTION");
            if(!proof.containsKey("failure")){proof.setProperty("failure",failure.toString());proof.setProperty("failurePhase",String.valueOf(phase));}
            if(client){try{restoreCutWitness();}catch(Exception cleanup){proof.setProperty("cutWitnessCleanupFailure",cleanup.toString());}try{disconnectAndReleaseClientDimension();}catch(Exception cleanup){proof.setProperty("clientDimensionMappingCleanupFailure",cleanup.toString());}}
            if(client){try{restoreClientPreferences("probe-failure");}catch(Exception cleanup){proof.setProperty("clientPreferencesRestoreFailure",cleanup.toString());}}
            if(server&&seedMode&&bridge!=null){try{call(bridge,"close");writeNbt("composite-server-oracle.nbt",call(bridge,"report"));}catch(Exception cleanup){proof.setProperty("fixtureCleanupFailure",cleanup.toString());}}
            persist("FAIL");
        }catch(Exception nested){proof.setProperty("secondaryReceiptFailure",nested.toString());nested.printStackTrace();}
        done=true;
        try{if(client&&mc!=null)call(mc,"func_71400_g");if(server)call(call(Class.forName("net.minecraft.server.MinecraftServer"),"func_71276_C"),"func_71263_m");}catch(Exception ignored){}
    }
    private File file(String name){return new File(output,name);}
    private Properties read(String name)throws Exception {
        Properties properties=new Properties();Path receipt=receiptPath(name);
        // Windows NIO opens with read/write/delete sharing by default; do not use legacy FileInputStream here.
        try(InputStream stream=Files.newInputStream(receipt)){properties.load(stream);}
        catch(java.nio.file.NoSuchFileException absent){return new Properties();}
        catch(IOException failure){throw new ReceiptIoException("read "+name,failure);}
        return properties;
    }
    private void store(String name,Properties value)throws Exception {
        Path target=receiptPath(name),temporary=receiptPath(name+".tmp");
        try{
            try(FileOutputStream stream=new FileOutputStream(temporary.toFile())){value.store(stream,"actual native composite witness");stream.getFD().sync();}
            atomicReceiptMove(temporary,target);
        }catch(IOException failure){throw new ReceiptIoException("store "+name,failure);}
    }
    private void writeNbt(String name,Object value)throws Exception {
        Path target=receiptPath(name),temporary=receiptPath(name+".tmp");
        try{
            try(DataOutputStream stream=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary.toFile()),64*1024))){call(Class.forName("net.minecraft.nbt.CompressedStreamTools"),"func_74800_a",value,stream);}
            atomicReceiptMove(temporary,target);
        }
        catch(Exception failure){Throwable cause=failure;while(cause instanceof java.lang.reflect.InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof IOException)throw new ReceiptIoException("write NBT "+name,(IOException)cause);throw failure;}
    }
    private void persistAeSamples()throws Exception {
        for(String kind:new String[]{"source","self","share","raw","rawCutSource","shareBank"}){
            Object sample=call(bridge,"pendingAeSample",kind);if(sample==null)continue;
            String name="composite-"+kind+"-ae-network.nbt";Path target=receiptPath(name);
            check(!Files.exists(target),"fixed AE sample artifact written only once");
            writeNbt(name,sample);
            byte[] actual=Files.readAllBytes(target);
            call(bridge,"commitAeSample",kind,name,hash(actual),(long)actual.length);
        }
    }
    private void persistRawSamples()throws Exception {
        for(String kind:new String[]{"durable-entry","baseline-to-cut-differences","postnative-differences","ae-relocation-proof"}){
            Object sample=call(bridge,"pendingRawSample",kind);if(sample==null)continue;
            String name="composite-raw-"+kind+".nbt";Path target=receiptPath(name);check(!Files.exists(target),"raw evidence artifact written only once");writeNbt(name,sample);
            byte[] bytes=Files.readAllBytes(target);call(bridge,"commitRawSample",kind,name,hash(bytes),(long)bytes.length);
        }
    }
    private Path receiptPath(String name)throws IOException {
        String base=name.endsWith(".tmp")?name.substring(0,name.length()-4):name;
        check(Arrays.asList("composite-client.properties","composite-server.properties","composite-client-timing.properties","composite-server-timing.properties","fixture-request.properties",
            "composite-manifest.nbt","composite-actor.nbt","composite-seed-receipt.nbt","composite-source-baseline.nbt","composite-server-oracle.nbt","composite-native-lab-failure.nbt",
            "composite-source-ae-network.nbt","composite-self-ae-network.nbt","composite-share-ae-network.nbt","composite-raw-ae-network.nbt",
            "composite-shareBank-ae-network.nbt",
            "composite-rawCutSource-ae-network.nbt","composite-raw-cut-ack.nbt","composite-raw-durable-entry.nbt","composite-raw-baseline-to-cut-differences.nbt","composite-raw-postnative-differences.nbt","composite-raw-ae-relocation-proof.nbt",
            "composite-raw-preview-transport.nbt","composite-raw-preview-reply.nbt","composite-raw-preview-render.nbt",
            "composite-self-private-import.nbt","composite-share-private-import.nbt",
            "composite-self-blocked-control.nbt","composite-share-blocked-control.nbt","composite-raw-blocked-control.nbt",
            "composite-self-terminal-failure.nbt","composite-share-terminal-failure.nbt","composite-raw-terminal-failure.nbt").contains(base),"fixed native receipt name required");
        Path path=file(name).toPath().toAbsolutePath().normalize();
        check(path.getParent().equals(output.toPath())&&!Files.isSymbolicLink(path),"receipt remains directly inside the already admitted case directory");
        return path;
    }
    private void atomicReceiptMove(Path temporary,Path target)throws IOException {
        for(int attempt=0;;attempt++){
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return;}
            catch(IOException failure){
                if(attempt>=7||!windowsTransientReceiptMoveFailure(failure))throw failure;
                proof.setProperty("receiptAtomicMoveWindowsRetries",String.valueOf(Integer.parseInt(proof.getProperty("receiptAtomicMoveWindowsRetries","0"))+1));
                try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();failure.addSuppressed(interrupted);throw failure;}
            }
        }
    }
    private static boolean windowsTransientReceiptMoveFailure(IOException failure){
        if(!System.getProperty("os.name","").startsWith("Windows")||!(failure instanceof FileSystemException))return false;
        // NTFS replacement can transiently report ERROR_ACCESS_DENIED even for these owned writable receipts.
        // Retry only the same admitted atomic move; a persistent ACL denial still fails after the bounded attempts.
        if(failure instanceof AccessDeniedException)return true;
        String reason=((FileSystemException)failure).getReason();
        return "The process cannot access the file because it is being used by another process.".equals(reason)
            ||"The process cannot access the file because it is being used by another process".equals(reason)
            ||"另一个程序正在使用此文件，进程无法访问。".equals(reason);
    }
    private static final class ReceiptIoException extends IOException {
        ReceiptIoException(String operation,IOException cause){super("Test receipt I/O "+operation+": "+cause,cause);}
    }
    private static final class NativeFixtureFailure extends IllegalStateException {
        NativeFixtureFailure(String detail){super(detail);}
    }
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
