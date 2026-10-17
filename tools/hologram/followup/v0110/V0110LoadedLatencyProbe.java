package gtmb.v0110.probe;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Addon only: real product actions, unmodified incoming envelopes, installed client state. */
@Mod(modid="gtmb_v0110_loaded_latency_probe", name="GTMB loaded common-version latency witness", version="1")
public final class V0110LoadedLatencyProbe {
    private static final String PRODUCT="com.miaokatze.gtmb.hologram.", CLIENT="com.miaokatze.gtmb.client.hologram.";
    private final Properties proof=new Properties(), measurements=new Properties();
    private final AtomicLong rx=new AtomicLong();
    private final List<Envelope> envelopes=new ArrayList<Envelope>();
    private File output;
    private Object mc, actor, metrics;
    private Consumer<Object> receiver;
    private boolean client,server,done,originalMetrics,metricsReady,ready,chunks,preRequestMatched,localApi,awaitingCancelAuthority;
    private long cancelRequestNanos,cancelRequestOrdinal,cancelOperationNumber;
    private boolean loadedRun,observingRender;
    private int loadedStep;
    private long settingsDrawnAt,promptExpectedSequence,promptBaselineRevision;
    private String loadedSession="",promptValue="";
    private int phase,ticks,round,step,placed,platform,cells,height;
    private String runPhase,paletteName="",operation="",stage="fixture";
    private long started,heartbeat,requested,requestOrdinal,operationNumber,firstEnvelope,ackAt,tickStart,stageSince,serverTicks;
    private Object metricBefore;
    private final Map<String,long[]> stageTotals=new LinkedHashMap<String,long[]>();
    private PrintWriter tickLog,stageLog,requestLog;
    private volatile String transportFailure="";
    private static final class Envelope {
        long ordinal,at; String session; int[] points;
        boolean selecting,hasSelection,fragment,error,selectionOnly,progressOnly,partial,cellDelta;
        int cellRows,fragmentIndex,fragmentTotal;
        long transportSequence;
    }

    @Mod.EventHandler public void init(FMLInitializationEvent event) throws Exception {
        client=Boolean.getBoolean("gtmb.latency.client"); server=Boolean.getBoolean("gtmb.latency.server");
        if (!client&&!server) return;
        check(client!=server,"exactly one fixture role");
        File fixture=new File(System.getProperty("gtmb.hologram.followupRoot","")).getCanonicalFile();
        check(fixture.getPath().replace('\\','/').endsWith("/build/hologram-packaged-v018-mui"),"owned fixture path");
        check(new File(".").getCanonicalFile().equals(new File(fixture,client?"client":"server").getCanonicalFile()),"owned fixture cwd");
        output=new File(System.getProperty("gtmb.v0110.output","")).getCanonicalFile();
        check(output.toPath().startsWith(new File(fixture.getParentFile(),"verification/render-v0110").getCanonicalFile().toPath())&&output.isDirectory(),"owned output");
        check(!file(client?"latency-client.properties":"latency-server.properties").exists(),"fresh receipts");
        cells=Integer.getInteger("gtmb.latency.cells",50000); check(cells==50000||cells==100000,"50k/100k only");
        height=cells/2000;
        runPhase=System.getProperty("gtmb.latency.phase","import");
        check(runPhase.equals("import"),"loaded case uses one real import prefix only");
        proof.setProperty("scope","real dedicated Netty product actions and installed authoritative client state; no OS input, synthetic ACK, replacement product classes, Save or FPS/TPS claim");
        proof.setProperty("requestedSourceCells",String.valueOf(cells)); proof.setProperty("sourceDimensionsXYZ","80,"+height+",25");
        proof.setProperty("phaseMode",runPhase); proof.setProperty("warmRounds","0"); proof.setProperty("measuredRounds","0"); proof.setProperty("loadedRounds","1");
        proof.setProperty("measurementResolution","nanotime at request and first main-thread observation after natural product installation; tick polling can add <=one normal client tick, callback often earlier");
        proof.setProperty("authorityWitness","one in-flight command; predicate false before request, real receiver ordinal after request, then product-installed state predicate true; full transport fragments are causal envelopes, never decoded ACK timestamps");
        proof.setProperty("packetScope","real RuntimeMetrics codec endpoints; raw codec bytes exclude Forge/TCP framing; cumulative snapshots without reset; optional fields absent are unavailable");
        started=stageSince=System.nanoTime();
        if (server) {
            tickLog=writer("server-ticks.tsv","tick\tstartNanos\tendNanos\twallNanos\tstageBefore\tstageAfter\tclientOperation");
            stageLog=writer("server-stage-transitions.tsv","observedAtNanos\tboundary\tstage\tdone\ttotal\tplanCursor");
        } else requestLog=writer("client-requests.tsv","number\tround\twarm\taction\trequestNanos\tfirstEnvelopeNanos\tcompletionObservedNanos\telapsedNanos\trxOrdinalBefore\trxOrdinalAfter\tlocalImmediateMatched\tselectionOnlyEnvelope\tcompletionKind");
        beginMetrics(); FMLCommonHandler.instance().bus().register(this); persist("RUNNING");
    }

    private void beginMetrics() throws Exception {
        try { metrics=Class.forName(PRODUCT+"HologramRuntimeMetrics"); originalMetrics=(Boolean)call(metrics,"isEnabled"); call(metrics,"setEnabled",true); metricsReady=true; }
        catch (Exception e) { proof.setProperty("metricsUnavailable",e.toString()); }
        proof.setProperty("metricsAvailable",String.valueOf(metricsReady));
    }
    private Object metric(String name) throws Exception {
        if (!metricsReady) return null;
        Object value=call(metrics,"snapshot");
        call(Class.forName("net.minecraft.nbt.CompressedStreamTools"),"func_74795_b",value,file((client?"client":"server")+"-metrics-"+name+".nbt"));
        return value;
    }
    private void metricDelta(Object before,Object after,String prefix) throws Exception {
        if (before==null||after==null) return;
        Object a=call(before,"func_74775_l","network"), b=call(after,"func_74775_l","network");
        for (Object key:(Set<?>)call(a,"func_150296_c")) {
            if (!(Boolean)call(b,"func_74764_b",key.toString())) continue;
            Object ar=call(a,"func_74775_l",key.toString()),br=call(b,"func_74775_l",key.toString());
            for (Object field:(Set<?>)call(ar,"func_150296_c")) if ((Boolean)call(br,"func_74764_b",field.toString())) {
                long delta=(Long)call(br,"func_74763_f",field.toString())-(Long)call(ar,"func_74763_f",field.toString());
                measurements.setProperty(prefix+".codec."+key+"."+field,String.valueOf(delta));
            }
        }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST) public void clientTick(TickEvent.ClientTickEvent e) {
        if (!client||done||e.phase!=TickEvent.Phase.END) return;
        try {
            check(System.nanoTime()-started<2400000000000L,"overall timeout: incomplete is FAIL");
            check(transportFailure.isEmpty(),"actual transport error: "+transportFailure);
            if (mc==null) { mc=call(Class.forName("net.minecraft.client.Minecraft"),"func_71410_x"); set(get(mc,"field_71474_y"),"field_82881_y",false); }
            ticks++;
            Object world=get(mc,"field_71441_e"), player=get(mc,"field_71439_g"), gui=get(mc,"field_71462_r");
            if (phase==0) {
                if (ticks<30||gui==null) return;
                Object fml=call(Class.forName("cpw.mods.fml.client.FMLClientHandler"),"instance"); call(fml,"setupServerList");
                call(fml,"connectToServer",gui,construct("net.minecraft.client.multiplayer.ServerData","GTMB latency",System.getProperty("gtmb.v0110.address","127.0.0.1:25678"))); phase=1; return;
            }
            if (world==null||player==null) { check(phase<2,"connected test world was lost before completion"); return; }
            check(!(Boolean)call(call(call(mc,"func_147114_u"),"func_147298_b"),"func_150731_c"),"real dedicated socket");
            if (phase==1) {
                if (!file("latency-fixture-ready.properties").isFile()) return;
                Object held=call(player,"func_70694_bm");
                if (held==null||!call(held,"func_77973_b").getClass().getName().endsWith("ItemNekoHologramProjector")) return;
                Object source=call(world,"func_72964_e",0,0), actorChunk=call(world,"func_72964_e",2,-1);
                if (source.getClass().getName().endsWith("EmptyChunk")||actorChunk.getClass().getName().endsWith("EmptyChunk")) return;
                try { Class.forName(CLIENT+"HologramClient").getDeclaredMethod("localAreaPoint",int.class,int.class,int.class,int.class,String.class);localApi=true; }
                catch(NoSuchMethodException oldVersion) {localApi=false;}
                proof.setProperty("pointStimulus",localApi?"production localAreaPoint + localAreaSelection; local completion is not server ACK":"legacy product Action + installed server authority; local API absent");
                // Give both versions the same benchmark-only socket idle limit so a slow baseline can finish.
                // This does not alter product tasks, codecs, work budgets, or completion predicates.
                Object pipeline=call(call(call(call(mc,"func_147114_u"),"func_147298_b"),"channel"),"pipeline");
                call(pipeline,"replace","timeout","timeout",construct("io.netty.handler.timeout.ReadTimeoutHandler",300));
                proof.setProperty("benchmarkSocketReadTimeoutSeconds","300");
                installReceiver(); metric("start"); phase=2;
                if (runPhase.equals("import")) { round=7; step=0; }
            }
            observeInstalled();
            if (!operation.isEmpty()) { check(System.nanoTime()-requested<(operation.equals("finishAreaSelection")?1800000000000L:loadedRun?300000000000L:120000000000L),"request timeout "+operation); pulse(); return; }
            if(awaitingCancelAuthority) {
                Object state=get(Class.forName(CLIENT+"HologramClient"),"state");
                if(state!=null&&!bool(get(state,"data"),"areaSelecting")&&rx.get()>cancelRequestOrdinal) {
                    awaitingCancelAuthority=false;
                    measurements.setProperty("request."+cancelOperationNumber+".cancelAuthoritySettledNanos",String.valueOf(System.nanoTime()));
                    measurements.setProperty("request."+cancelOperationNumber+".cancelAuthoritySettledElapsedNanos",String.valueOf(System.nanoTime()-cancelRequestNanos));
                    store("client-latency.properties",measurements);
                } else {check(System.nanoTime()-cancelRequestNanos<120000000000L,"actual server cancellation cleanup timeout");pulse();return;}
            }
            if (phase==3) { finishClient(); return; }
            if (phase==4) {
                checkLoadedClient();
                String[] loadedActions={"beginAreaSelection","areaPoint1","areaPoint2","undoAreaSelection","cancelAreaSelection","openSettings","editCellPrompt"};
                issue(loadedActions[loadedStep]);return;
            }
            if (round>=7&&!runPhase.equals("selection")&&step>=4) { issue("finishAreaSelection"); return; }
            if (round>=7&&runPhase.equals("selection")) { phase=3; return; }
            if (step==0) issue("beginAreaSelection");
            else if (step==1) issue("areaPoint1");
            else if (step==2) issue("areaPoint2");
            else if (round<7) issue("cancelAreaSelection");
            else step=4;
            pulse();
        } catch (Throwable failure) { fail(failure); }
    }

    private void checkLoadedClient()throws Exception {
        Object state=get(Class.forName(CLIENT+"HologramClient"),"state");
        check(state!=null&&loadedSession.equals(get(state,"session"))&&((List<?>)get(state,"cells")).size()==cells,"loaded client retains same authoritative 50k/100k baseline session and cells");
        proof.setProperty("loadedClientCaptureRetained","true");
    }
    private void sendPrompt()throws Exception {
        Object gui=get(mc,"field_71462_r");check(gui!=null&&gui.getClass().getName().equals(CLIENT+"HologramScreen"),"actual current settings screen");
        Object ui=get(gui,"ui"),session=get(ui,"session"),preview=get(ui,"preview");
        check((Boolean)call(session,"editable"),"real UI session editable");
        Object selection=call(preview,"selection");call(selection,"click",0,false);call(preview,"selected",0);
        check(Arrays.equals((int[])call(selection,"indices"),new int[]{0})&&(Integer)call(preview,"selected")==0,"real UI selection contains only actual cell zero");
        Object state=get(Class.forName(CLIENT+"HologramClient"),"state");promptBaselineRevision=(Long)call(get(state,"data"),"func_74763_f","planRevision");
        Object flags=construct("net.minecraft.nbt.NBTTagCompound");call(flags,"func_74783_a","indices",new int[]{0});call(flags,"func_74778_a","prompt",promptValue);
        call(session,"send","editCellFlags",flags,true);promptExpectedSequence=(Long)get(session,"sequence");
        check(promptExpectedSequence>0,"actual production UI sequence allocated");
        proof.setProperty("promptStimulus","actual current HologramUiSession.send editCellFlags with real UI selected cell zero; no pin/NBT command");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)public void naturalRender(TickEvent.RenderTickEvent e) {
        if(!client||done||e.phase!=TickEvent.Phase.END||!operation.equals("openSettings"))return;
        try {
            Object gui=get(mc,"field_71462_r");if(gui==null||!gui.getClass().getName().equals(CLIENT+"HologramScreen"))return;
            Object ui=get(gui,"ui"),session=get(ui,"session"),canvas=get(ui,"canvas"),state=get(Class.forName(CLIENT+"HologramClient"),"state");
            long generation=(Long)call(canvas,"renderGeneration");
            if(generation<=0||get(session,"state")!=state)return;
            settingsDrawnAt=System.nanoTime();proof.setProperty("settingsNaturalRenderGeneration",String.valueOf(generation));proof.setProperty("settingsActualScreenIdentity",String.valueOf(System.identityHashCode(gui)));
            observingRender=true;try{observeInstalled();}finally{observingRender=false;}
        }catch(Throwable failure){fail(failure);}
    }

    private void installReceiver() throws Exception {
        final Object network=Class.forName(PRODUCT+"HologramNetwork");
        receiver=(Consumer<Object>)get(network,"receiver"); check(receiver!=null,"real product receiver");
        member((Class<?>)network,"receiver").set(null,new Consumer<Object>() { public void accept(Object actual) {
            try {
                Envelope v=new Envelope(); v.ordinal=rx.incrementAndGet(); v.at=System.nanoTime();
                v.fragment=bool(actual,"transportFragment"); v.error=bool(actual,"transportError");
                v.hasSelection=(Boolean)call(actual,"func_74764_b","areaSelecting"); v.selecting=bool(actual,"areaSelecting");
                v.selectionOnly=bool(actual,"areaSelectionOnly"); v.points=(int[])call(actual,"func_74759_k","areaPoints"); v.session=str(actual,"session");
                v.progressOnly=bool(actual,"progressOnly");v.partial=bool(actual,"partial");v.cellDelta=bool(actual,"cellDelta");
                v.cellRows=(Integer)call(call(actual,"func_150295_c","cells",10),"func_74745_c");
                v.fragmentIndex=(Integer)call(actual,"func_74762_e","transportIndex");v.fragmentTotal=(Integer)call(actual,"func_74762_e","transportTotal");v.transportSequence=(Long)call(actual,"func_74763_f","transportSequence");
                if (v.error) transportFailure=actual.toString();
                synchronized(envelopes) { envelopes.add(v); if(envelopes.size()>4096) envelopes.remove(0); }
            } catch(Exception failure) { transportFailure="observer: "+failure; }
            receiver.accept(actual); // Exactly the original actual packet is forwarded once.
            try { call(mc,"func_152344_a",new Runnable() { public void run() { try { observeInstalled(); } catch(Throwable failure) { fail(failure); } } }); }
            catch(Exception failure) { transportFailure="main observation scheduling: "+failure; }
        }});
    }
    private void issue(String action) throws Exception {
        operation=action; operationNumber++; ackAt=0; firstEnvelope=0;
        preRequestMatched=localStep()?matchesLocal():matchesState();
        check(!preRequestMatched,"request must begin before its installed authority predicate matches: "+action);
        measurements.setProperty("request."+operationNumber+".preRequestMatched",String.valueOf(preRequestMatched));
        metricBefore=metric(String.format("%03d-before",operationNumber));
        proof.setProperty("operation",action); proof.setProperty("operationNumber",String.valueOf(operationNumber));
        proof.setProperty("round",String.valueOf(round)); persist("RUNNING");
        requestOrdinal=rx.get(); requested=System.nanoTime();
        if(action.equals("beginAreaSelection")) call(Class.forName(CLIENT+"HologramClient"),"beginAreaSelection");
        else if(action.equals("openSettings")) call(Class.forName(CLIENT+"HologramClient"),"openSettings");
        else if(action.equals("editCellPrompt")) sendPrompt();
        else if(action.equals("undoAreaSelection")) {
            if(localApi)check((Boolean)call(Class.forName(CLIENT+"HologramClient"),"undoLocalAreaSelection"),"production local undo accepted");
            else {
                Object state=get(Class.forName(CLIENT+"HologramClient"),"state"),p=construct("net.minecraft.nbt.NBTTagCompound");
                call(p,"func_74778_a","op","undoAreaSelection");call(p,"func_74778_a","session",get(state,"session"));
                call(p,"func_74772_a","planRevision",call(get(state,"data"),"func_74763_f","planRevision"));
                call(Class.forName(PRODUCT+"HologramNetwork"),"sendAction",p);
            }
        }
        else if(action.startsWith("areaPoint")) {
            int point=action.endsWith("1")?1:2;
            if(localApi) {
                check((Boolean)call(Class.forName(CLIENT+"HologramClient"),"localAreaPoint",point,point==1?0:79,point==1?80:79+height,point==1?0:24,"replace"),"production local point accepted");
            } else {
            Object state=get(Class.forName(CLIENT+"HologramClient"),"state"), p=construct("net.minecraft.nbt.NBTTagCompound");
            call(p,"func_74778_a","action","areaPoint"); call(p,"func_74778_a","session",get(state,"session"));
            call(p,"func_74772_a","planRevision",call(get(state,"data"),"func_74763_f","planRevision"));
            for(Object[] v:new Object[][]{{"point",point},{"x",point==1?0:79},{"y",point==1?80:79+height},{"z",point==1?0:24}}) call(p,"func_74768_a",v[0],v[1]);
            call(p,"func_74778_a","mode","replace"); call(Class.forName(PRODUCT+"HologramNetwork"),"sendAction",p);
            }
        } else {
            if(localApi&&action.equals("finishAreaSelection")) {
                Object selection=call(Class.forName(CLIENT+"HologramClient"),"localAreaSelection");
                check(selection!=null&&Arrays.equals((int[])call(selection,"func_74759_k","areaPoints"),new int[]{0,80,0,79,79+height,24}),"actual local points complete before real finish");
            }
            call(Class.forName(CLIENT+"HologramClient"),"worldAction",action);
        }
        boolean immediate=localApi?matchesLocal():matchesState();
        measurements.setProperty("request."+operationNumber+".localImmediateMatched",String.valueOf(immediate));
        if(localApi&&immediate) {
            measurements.setProperty("request."+operationNumber+".localObservedNanos",String.valueOf(System.nanoTime()));
            measurements.setProperty("request."+operationNumber+".localElapsedNanos",String.valueOf(System.nanoTime()-requested));
        }
        measurements.setProperty("request."+operationNumber+".requestedNanos",String.valueOf(requested));
        if(localStep())observeInstalled();
    }
    private boolean localStep(){return localApi&&(operation.startsWith("areaPoint")||operation.equals("beginAreaSelection")||operation.equals("undoAreaSelection")||operation.equals("cancelAreaSelection"));}
    private boolean matchesLocal()throws Exception{
        if(!localApi)return false;
        Object local=call(Class.forName(CLIENT+"HologramClient"),"localAreaSelection");
        if(operation.equals("cancelAreaSelection"))return local==null;
        return local!=null&&predicate(bool(local,"areaSelecting"),(int[])call(local,"func_74759_k","areaPoints"));
    }
    private boolean predicate(boolean selecting,int[] points) {
        if(operation.equals("beginAreaSelection")) return selecting&&points.length==0;
        if(operation.equals("undoAreaSelection"))return selecting&&points.length==0;
        if(operation.equals("areaPoint1")) return selecting&&Arrays.equals(points,new int[]{0,80,0});
        if(operation.equals("areaPoint2")) return selecting&&Arrays.equals(points,new int[]{0,80,0,79,79+height,24});
        return operation.equals("cancelAreaSelection")&&!selecting&&points.length==0;
    }
    private boolean matchesState() throws Exception {
        Object state=get(Class.forName(CLIENT+"HologramClient"),"state"); if(state==null) return false;
        Object data=get(state,"data");
        if(operation.equals("openSettings"))return observingRender&&settingsDrawnAt>=requested&&get(state,"session").equals(loadedSession)&&((List<?>)get(state,"cells")).size()==cells;
        if(operation.equals("editCellPrompt"))return get(state,"session").equals(loadedSession)&&((List<?>)get(state,"cells")).size()==cells&&promptValue.equals(get(((List<?>)get(state,"cells")).get(0),"prompt"))&&(Long)call(data,"func_74763_f","uiSequence")>=promptExpectedSequence;
        if(operation.equals("finishAreaSelection")) return bool(data,"areaImported")&&!bool(data,"areaImporting")&&!bool(data,"capturePending")&&((List<?>)get(state,"cells")).size()==cells&&!busy();
        return predicate(bool(data,"areaSelecting"),(int[])call(data,"func_74759_k","areaPoints"));
    }
    private boolean busy() throws Exception {
        try { return (Boolean)call(Class.forName(CLIENT+"HologramBackgroundTasks"),"busy"); }
        catch(ClassNotFoundException absent) { return false; }
        catch(NoSuchMethodException absent) { return false; }
    }
    private void observeInstalled() throws Exception {
        if(operation.isEmpty()||ackAt!=0||preRequestMatched) return;
        boolean local=localStep();if(local?!matchesLocal():!matchesState())return;
        Object state=get(Class.forName(CLIENT+"HologramClient"),"state"); String session=state==null?"":String.valueOf(get(state,"session")); Envelope matched=null;
        if(!local)synchronized(envelopes) { for(Envelope v:envelopes) if(v.ordinal>requestOrdinal&&v.at>=requested&&!v.error) {
            if(v.fragment || ((operation.equals("finishAreaSelection")||operation.equals("openSettings")||operation.equals("editCellPrompt")) ? v.session.equals(session) : v.hasSelection&&v.session.equals(session)&&predicate(v.selecting,v.points))) { matched=v; break; }
        }}
        if(!local&&matched==null) return;
        ackAt=System.nanoTime(); firstEnvelope=local?0:matched.at;
        String prefix="request."+operationNumber;
        measurements.setProperty(prefix+".action",operation); measurements.setProperty(prefix+".warm",String.valueOf(round<2));
        measurements.setProperty(prefix+".elapsedNanos",String.valueOf(ackAt-requested));
        measurements.setProperty(prefix+".firstEnvelopeNanos",String.valueOf(firstEnvelope));
        measurements.setProperty(prefix+(local?".localObservedNanos":".installedObservedNanos"),String.valueOf(ackAt));
        measurements.setProperty(prefix+(local?".localElapsedNanos":".authorityElapsedNanos"),String.valueOf(ackAt-requested));
        measurements.setProperty(prefix+".completionKind",local?"clientLocal":"installedAuthority");
        measurements.setProperty(prefix+".selectionOnlyEnvelope",String.valueOf(!local&&matched.selectionOnly));
        measurements.setProperty(prefix+".firstEnvelopeKind",local?"none-local-completion":matched.fragment?"transportFragment-causal-only":"selection-scalars");
        metricDelta(metricBefore,metric(String.format("%03d-after",operationNumber)),prefix);
        if(loadedRun) synchronized(envelopes) {
            int observed=0;
            for(Envelope v:envelopes)if(v.ordinal>requestOrdinal&&v.at>=requested) {
                String row=prefix+".actualEnvelope."+(observed++);
                measurements.setProperty(row+".kind",v.fragment?"transportFragment":v.progressOnly?"progressOnly":v.cellDelta?"cellDelta":v.partial?"partialDTO":"stateDTO");
                measurements.setProperty(row+".cellRows",String.valueOf(v.cellRows));measurements.setProperty(row+".transportSequence",String.valueOf(v.transportSequence));
                measurements.setProperty(row+".fragmentIndex",String.valueOf(v.fragmentIndex));measurements.setProperty(row+".fragmentTotal",String.valueOf(v.fragmentTotal));
            }
            measurements.setProperty(prefix+".actualEnvelopeCount",String.valueOf(observed));
        }
        requestLog.println(operationNumber+"\t"+round+"\t"+(round<2)+"\t"+operation+"\t"+requested+"\t"+firstEnvelope+"\t"+ackAt+"\t"+(ackAt-requested)+"\t"+requestOrdinal+"\t"+rx.get()+"\t"+measurements.getProperty(prefix+".localImmediateMatched")+"\t"+(!local&&matched.selectionOnly)+"\t"+(local?"clientLocal":"installedAuthority")); requestLog.flush();
        String completed=operation; operation="";
        synchronized(envelopes) { envelopes.clear(); }
        if(loadedRun) {
            checkLoadedClient();
            measurements.setProperty(prefix+".loadedCells",String.valueOf(cells));
            if(completed.equals("cancelAreaSelection")&&local){awaitingCancelAuthority=true;cancelRequestNanos=requested;cancelRequestOrdinal=requestOrdinal;cancelOperationNumber=operationNumber;}
            if(completed.equals("openSettings")){proof.setProperty("loadedSettingsNaturalRender","true");proof.setProperty("settingsRequestToNaturalRenderNanos",String.valueOf(ackAt-requested));}
            if(completed.equals("editCellPrompt")) {
                Object data=get(state,"data");proof.setProperty("loadedPromptAuthoritative","true");
                proof.setProperty("promptBaselineRevision",String.valueOf(promptBaselineRevision));proof.setProperty("promptAuthoritativeRevision",String.valueOf(call(data,"func_74763_f","planRevision")));
                proof.setProperty("promptAuthoritativeUiSequence",String.valueOf(call(data,"func_74763_f","uiSequence")));proof.setProperty("promptExpectedUiSequence",String.valueOf(promptExpectedSequence));
                proof.setProperty("promptInstalledPartial",String.valueOf(bool(data,"partial")));proof.setProperty("promptInstalledCellDelta",String.valueOf(bool(data,"cellDelta")));
            }
            loadedStep++;if(loadedStep==7){proof.setProperty("loadedInteractionCompleted","true");phase=3;}
        } else if(completed.equals("finishAreaSelection")) {
            proof.setProperty("actualCapturedCells",String.valueOf(cells));proof.setProperty("importElapsedNanos",String.valueOf(ackAt-requested));
            loadedSession=String.valueOf(get(state,"session"));proof.setProperty("loadedSession",loadedSession);promptValue="loaded-"+output.getName();
            proof.setProperty("promptExpectedValue",promptValue);
            loadedRun=true;loadedStep=0;round=8;phase=4;
        } else step++;
        store("client-latency.properties",measurements); persist("RUNNING");
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST) public void serverStart(TickEvent.ServerTickEvent e) {
        if(!server||done||e.phase!=TickEvent.Phase.START) return;
        try { tickStart=System.nanoTime(); observeServer("START"); } catch(Throwable failure) { fail(failure); }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void serverEnd(TickEvent.ServerTickEvent e) {
        if(!server||done||e.phase!=TickEvent.Phase.END) return;
        try {
            String before=stage; long end=System.nanoTime(); serverTicks++;
            observeServer("END");
            Properties peer=read("latency-client.properties");
            tickLog.println(serverTicks+"\t"+tickStart+"\t"+end+"\t"+(end-tickStart)+"\t"+before+"\t"+stage+"\t"+peer.getProperty("operationNumber","0"));
            long[] totals=stageTotals.get(before); if(totals==null){totals=new long[3];stageTotals.put(before,totals);} totals[0]++;totals[1]+=end-tickStart;totals[2]=Math.max(totals[2],end-tickStart);
            check(end-started<2400000000000L,"server overall timeout");
            Object host=call(Class.forName("net.minecraft.server.MinecraftServer"),"func_71276_C");
            Object world=call(host,"func_71218_a",0);
            String worldName=String.valueOf(call(call(world,"func_72912_H"),"func_76065_j")); check(worldName.startsWith("hologram-v0110-"),"owned fresh world");
            call(world,"func_72877_b",6000L);
            prepareFixture(host,world);
            if(actor!=null&&"4".equals(peer.getProperty("driverPhase"))) {
                Object active=call(Class.forName(PRODUCT+"HologramWorkspace"),"find",call(actor,"func_110124_au"));
                Object capture=active==null?null:get(active,"capture");
                int count=capture==null?0:((List<?>)get(capture,"cells")).size();
                check(count==cells,"loaded server capture retained at every observed tick: "+count);
                proof.setProperty("loadedServerCaptureCells",String.valueOf(count));
                proof.setProperty("loadedServerCaptureSession",String.valueOf(get(active,"id")));
                proof.setProperty("loadedServerCaptureObserved","true");
            }
            if("PASS".equals(peer.getProperty("status"))||"FAIL".equals(peer.getProperty("status"))) {
                if("PASS".equals(peer.getProperty("status"))) {
                    Object active=call(Class.forName(PRODUCT+"HologramWorkspace"),"find",call(actor,"func_110124_au")),capture=get(active,"capture");
                    List<?> actualCells=(List<?>)get(capture,"cells");
                    check(actualCells.size()==cells&&peer.getProperty("promptExpectedValue").equals(get(actualCells.get(0),"prompt")),"real retained server capture and actual server cell-zero prompt committed");
                    proof.setProperty("loadedServerPromptConfirmed","true");
                }
                proof.setProperty("clientVerdict",peer.getProperty("status")); proof.setProperty("configRestored","true");
                finishServerMetrics(); restoreMetrics(); persist("COMPLETE"); done=true; closeLogs(); call(host,"func_71263_m"); return;
            }
            if(end-heartbeat>1000000000L) { heartbeat=end; tickLog.flush();stageLog.flush(); writeServerMeasurements(); persist("RUNNING"); }
        } catch(Throwable failure) { fail(failure); }
    }
    private void observeServer(String boundary) throws Exception {
        Object session=null,area=null;
        if(actor!=null) {
            Object id=call(actor,"func_110124_au"); session=call(Class.forName(PRODUCT+"HologramWorkspace"),"find",id);
            area=((Map<?,?>)get(Class.forName(PRODUCT+"HologramWorkspace"),"AREAS")).get(id);
        }
        String next=ready?"idle":"fixture",progress="",total="",cursor="";
        if(area!=null) {
            Object importing=optional(area,"importing");
            if(Boolean.TRUE.equals(importing)) {
                next="area."+String.valueOf(optional(area,"importPhase"));
                Object task=optional(area,"planTask"); if(task!=null) {next+=".planStage="+optional(task,"stage");cursor=String.valueOf(optional(task,"cursor"));}
                try { next+=".detail="+call(area,"importDetail"); } catch(NoSuchMethodException absent) { }
                try { progress=String.valueOf(call(area,"importDone"));total=String.valueOf(call(area,"importTotal")); } catch(NoSuchMethodException absent) { }
            } else if(Boolean.TRUE.equals(optional(area,"selecting"))) next="selection";
        }
        if(session!=null) {
            Object prep=null;
            try { Object tasks=get(Class.forName(PRODUCT+"HologramSnapshotPreparation"),"TASKS"); prep=((Map<?,?>)tasks).get(session); }
            catch(ClassNotFoundException absent) {} catch(NoSuchFieldException absent) {}
            if(prep!=null) {next+=".snapshot="+optional(prep,"stage");Object metadata=optional(prep,"metadata");if(metadata!=null)next+=".metadata="+optional(metadata,"stage");}
            Object resource=optional(session,"resourceTask"); if(resource!=null) next+=".resource="+optional(resource,"stage");
        }
        if(!next.equals(stage)) {
            long now=System.nanoTime(); String key="stage."+stage+".observedWallNanos";
            long prior=Long.parseLong(measurements.getProperty(key,"0"));measurements.setProperty(key,String.valueOf(prior+now-stageSince));
            stage=next;stageSince=now; stageLog.println(now+"\t"+boundary+"\t"+stage+"\t"+progress+"\t"+total+"\t"+cursor); stageLog.flush();
        }
        proof.setProperty("observedStage",stage);proof.setProperty("observedWorkDone",progress);proof.setProperty("observedWorkTotal",total);proof.setProperty("observedPlanCursor",cursor);
    }

    private void prepareFixture(Object host,Object world) throws Exception {
        if(!chunks) {
            call(Class.forName(PRODUCT+"HologramCutChunks"),"load",world,-16,-64,112,97); chunks=true;
        }
        Object registry=get(Class.forName("net.minecraft.block.Block"),"field_149771_c"); Object stone=call(registry,"func_82594_a","minecraft:stone");
        if(!ready) {
            int platformTotal=112*97;
            for(int end=Math.min(platformTotal,platform+512);platform<end;platform++) put(world,platform%112-16,79,platform/112-64,stone,0);
            if(platform<platformTotal)return;
            call(call(world,"func_72912_H"),"func_76081_a",40,80,-40);
            Object decorative=null;
            for(String name:new String[]{"chisel:stone","chisel:stonebricks","chisel:marble","chisel:limestone"}) {
                Object candidate=call(registry,"func_82594_a",name);
                if(candidate!=null&&!candidate.equals(call(registry,"func_82594_a","minecraft:air"))) {decorative=candidate;paletteName=name;break;}
            }
            check(decorative!=null,"fixture must expose real registered Chisel decorative block");
            Object wool=call(registry,"func_82594_a","minecraft:wool"),glass=call(registry,"func_82594_a","minecraft:stained_glass");
            Object[] palette={stone,wool,glass,decorative};
            for(int end=Math.min(cells,placed+256);placed<end;placed++) {
                int n=placed,kind=n%4; put(world,n%80,80+n/2000,n/80%25,palette[kind],kind==1||kind==2?(n/4)%16:0);
            }
            if(placed<cells)return;
            proof.setProperty("palette","minecraft:stone,minecraft:wool[0..15],minecraft:stained_glass[0..15],"+paletteName+"[0]");
            proof.setProperty("paletteFormula","kind=n%4; vanilla dyed meta=(n/4)%16; no tile entities or NBT variants in this ordinary-decoration phase");
            proof.setProperty("actualSourceBlocks",String.valueOf(placed)); ready=true;
            for(int x=36;x<=44;x++) for(int z=-10;z<=-2;z++) put(world,x,79+height/2,z,stone,0);
            call(call(world,"func_72912_H"),"func_76081_a",40,80+height/2,-6);
        }
        List<?> players=(List<?>)get(call(host,"func_71203_ab"),"field_72404_b");check(players.size()<=1,"single owned player");
        if(actor==null&&!players.isEmpty()) {
            actor=players.get(0); call(get(actor,"field_71135_a"),"func_147364_a",40.5d,(double)(80+height/2),-5.5d,180f,-20f);
            call(call(host,"func_71203_ab"),"func_72358_d",actor);
            Object inventory=get(actor,"field_71071_by"), item=call(get(Class.forName("net.minecraft.item.Item"),"field_150901_e"),"func_82594_a","gtmb:neko_hologram_projector");
            Object tool=construct("net.minecraft.item.ItemStack",item), settings=construct(PRODUCT+"HologramMaterials$Settings");
            set(settings,"me",false);set(settings,"containers",false);call(Class.forName(PRODUCT+"HologramMaterials"),"write",tool,settings);
            proof.setProperty("fixtureMaterialSettings","main=true,me=false,containers=false; same real tool settings on both versions; product classification unchanged");
            Array.set(get(inventory,"field_70462_a"),0,tool);set(inventory,"field_70461_c",0);
            call(get(actor,"field_71069_bz"),"func_75142_b");proof.setProperty("world",String.valueOf(call(call(world,"func_72912_H"),"func_76065_j")));
            store("latency-fixture-ready.properties",proof);metric("fixture-ready");
        }
    }
    private static void put(Object world,int x,int y,int z,Object block,int meta) throws Exception { check((Boolean)call(world,"func_147465_d",x,y,z,block,meta,2),"actual source write failed at "+x+","+y+","+z); }
    private void finishServerMetrics() throws Exception {
        writeServerMeasurements();metric("terminal");
    }
    private void writeServerMeasurements() throws Exception {
        measurements.setProperty("scope","FML ServerTick START(HIGHEST)..END(LOWEST), game and addon listener wall; stage times are observed residency, not exclusive CPU; transitions inside one synchronous product call are unavailable; not TPS");
        measurements.setProperty("observedAtNanos",String.valueOf(System.nanoTime()));measurements.setProperty("currentStage",stage);measurements.setProperty("currentStageUnclosedWallNanos",String.valueOf(System.nanoTime()-stageSince));
        for(Map.Entry<String,long[]> row:stageTotals.entrySet()) {String key="tickStage."+row.getKey();long[] v=row.getValue();measurements.setProperty(key+".ticks",String.valueOf(v[0]));measurements.setProperty(key+".totalWallNanos",String.valueOf(v[1]));measurements.setProperty(key+".maxWallNanos",String.valueOf(v[2]));}
        store("server-tick-and-stage-metrics.properties",measurements);
    }
    private void finishClient() throws Exception {
        proof.setProperty("selectionCompletedRounds",String.valueOf(runPhase.equals("import")?0:round));
        check(runPhase.equals("import")||round==7,"two warm and five measured complete rounds");
        if(!runPhase.equals("selection"))check(proof.getProperty("actualCapturedCells","").equals(String.valueOf(cells)),"actual full capture required");
        check("true".equals(proof.getProperty("loadedInteractionCompleted"))&&"true".equals(proof.getProperty("loadedSettingsNaturalRender"))&&"true".equals(proof.getProperty("loadedPromptAuthoritative")),"loaded round, naturally drawn settings and real prompt authority required");
        restoreReceiver();metric("terminal");restoreMetrics();proof.setProperty("preferencesRestored","true");
        persist("PASS");done=true;closeLogs();call(mc,"func_71400_g");
    }
    private void restoreReceiver() throws Exception {if(receiver!=null){member(Class.forName(PRODUCT+"HologramNetwork"),"receiver").set(null,receiver);receiver=null;}}
    private void restoreMetrics() throws Exception {if(metricsReady){call(metrics,"setEnabled",originalMetrics);metricsReady=false;}proof.setProperty("metricsRestored","true");}
    private void pulse() throws Exception {if(System.nanoTime()-heartbeat>1000000000L){heartbeat=System.nanoTime();persist("RUNNING");}}
    private void fail(Throwable failure) {
        if(done)return;failure.printStackTrace();proof.setProperty("failure",failure.toString());
        try { if(client)restoreReceiver();else finishServerMetrics();restoreMetrics();persist("FAIL"); }catch(Exception e){e.printStackTrace();}
        done=true;closeLogs();try{if(client&&mc!=null)call(mc,"func_71400_g");else call(call(Class.forName("net.minecraft.server.MinecraftServer"),"func_71276_C"),"func_71263_m");}catch(Exception e){e.printStackTrace();}
    }
    private void closeLogs(){if(tickLog!=null)tickLog.close();if(stageLog!=null)stageLog.close();if(requestLog!=null)requestLog.close();}
    private PrintWriter writer(String name,String header)throws Exception{PrintWriter w=new PrintWriter(new OutputStreamWriter(new FileOutputStream(file(name)),"UTF-8"));w.println(header);w.flush();return w;}
    private File file(String name){return new File(output,name);}
    private void persist(String status)throws Exception{proof.setProperty("status",status);proof.setProperty("driverPhase",String.valueOf(phase));proof.setProperty("elapsedNanos",String.valueOf(System.nanoTime()-started));store(client?"latency-client.properties":"latency-server.properties",proof);}
    private Properties read(String name)throws Exception{Properties p=new Properties();try(FileInputStream in=new FileInputStream(file(name))){p.load(in);}catch(FileNotFoundException absent){}return p;}
    private void store(String name,Properties p)throws Exception{File temporary=file(name+".tmp");try(FileOutputStream out=new FileOutputStream(temporary)){p.store(out,"GTMB common-version real latency witness");}for(int n=0;n<10;n++)try{Files.move(temporary.toPath(),file(name).toPath(),StandardCopyOption.REPLACE_EXISTING);return;}catch(FileSystemException sharing){if(n==9)throw sharing;Thread.sleep(10);}}
    private static Object optional(Object target,String name)throws Exception{try{return get(target,name);}catch(NoSuchFieldException absent){return null;}}
    private static boolean bool(Object nbt,String name)throws Exception{return (Boolean)call(nbt,"func_74767_n",name);}
    private static String str(Object nbt,String name)throws Exception{return (String)call(nbt,"func_74779_i",name);}
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static Field member(Class<?> type,String name)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(type.getName()+"."+name);}
    private static Object get(Object o,String name)throws Exception{return member(o instanceof Class?(Class<?>)o:o.getClass(),name).get(o instanceof Class?null:o);}
    private static void set(Object o,String name,Object value)throws Exception{member(o.getClass(),name).set(o,value);}
    private static Object construct(String name,Object...args)throws Exception{for(Constructor<?> c:Class.forName(name).getDeclaredConstructors())if(compatible(c.getParameterTypes(),args)){c.setAccessible(true);return c.newInstance(args);}throw new NoSuchMethodException(name+" constructor");}
    private static Object call(Object o,String name,Object...args)throws Exception{Class<?> type=o instanceof Class?(Class<?>)o:o.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}throw new NoSuchMethodException(type.getName()+"."+name);}
    private static boolean compatible(Class<?>[] types,Object[] values){if(types.length!=values.length)return false;for(int i=0;i<types.length;i++){if(values[i]==null){if(types[i].isPrimitive())return false;continue;}Class<?> t=types[i];if(t.isPrimitive())t=t==int.class?Integer.class:t==long.class?Long.class:t==float.class?Float.class:t==double.class?Double.class:t==boolean.class?Boolean.class:t;if(!t.isInstance(values[i]))return false;}return true;}
}
