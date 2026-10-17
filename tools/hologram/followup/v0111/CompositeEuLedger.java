package gtmb.v0111.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in test observer of the existing GTSWN native hook; never supplies or changes energy. */
public final class CompositeEuLedger {
    public static final String PROPERTY = "gtmb.v0111.nativeEuLedger";
    private static final String PROBES = "com.miaokatze.gtswn.common.testworld.TestWorldProbes";
    private static final String[] CATEGORIES = {"CONSTRUCTION", "CACHE", "TRANSFER", "NATIVE_HATCH", "NATIVE_COVER", "NATIVE_COVER_REMOVAL", "SUPPLY", "TRANSACTION_REFUND", "CONSTRUCTION_REFUND", "UNKNOWN"};
    private final Object world, actor, session;
    private final int[] source, bank, target;
    private final Thread thread = Thread.currentThread();
    private final Map<String, BigInteger> sums = new LinkedHashMap<String, BigInteger>();
    private final Map<String, Long> counts = new LinkedHashMap<String, Long>();
    private final Map<UUID, UUID> owners = new LinkedHashMap<UUID, UUID>();
    private final List<String> diagnostics = new ArrayList<String>();
    private final List<String> planWarnings = new ArrayList<String>(), intrinsicSources = new ArrayList<String>();
    private final UUID actorOwner, leader;
    private final BigInteger construction;
    private BigInteger before = BigInteger.ZERO, cacheEu = BigInteger.ZERO;
    private BigDecimal cacheAe = BigDecimal.ZERO;
    private long commits, rejects;
    private volatile long errors;
    private Object nativeSession;
    private boolean active, finished;
    private boolean materialAeRouteSeen;
    private long materialRouteObservedSamples, materialRouteContextAbsentSamples;
    private int nativeEnergyCoverCount;
    private volatile String failure = "";
    private String quoteSources = "";

    public CompositeEuLedger(Object world, Object actor, Object session, int[] source, int[] bank,
        int[] target, BigInteger construction) throws Exception {
        this.world=world;this.actor=actor;this.session=session;this.source=source;this.bank=bank;this.target=target;
        this.construction=construction;actorOwner=(UUID)call(actor,"func_110124_au");leader=leader(actorOwner);
        for(String category:CATEGORIES){sums.put(category,BigInteger.ZERO);counts.put(category,0L);}
    }

    public void begin() throws Exception {
        require(Thread.currentThread()==thread,"observer must start on its server thread");
        require((Boolean)call(Class.forName("com.miaokatze.gtswn.common.util.DebugTestGate"),"isEnabled"),"native GTSWN startup gate is false");
        require("IDLE".equals(get(Class.forName("com.miaokatze.gtswn.common.testworld.TestWorldManager"),"phase")),"native scenario manager is not IDLE");
        Class<?> probes=Class.forName(PROBES);
        require(!(Boolean)call(probes,"enabled")&&get(probes,"session")==null,"existing global probe session must not be replaced");
        Object entity=call(actor,"getEntityData"),persisted=call(entity,"func_74775_l","PlayerPersisted");
        for(String key:new String[]{"GTSWN_TestUiKitBackup_v1","GTSWN_TestUiKitRecovery_v1"})
            require(!(Boolean)call(persisted,"func_74764_b",key),"actor has native test UI kit recovery data: "+key);
        require(((Integer)get(get(world,"field_73011_w"),"field_76574_g"))==1048578,"explicit GTMB fixture dimension only");
        owners.put(actorOwner,leader);inspectOwners(false);
        freezeCacheMetadata();quote();
        // This observer proves the EU account only; it must not assert AE-account payment from EU events.
        require(cacheAe.signum()==0,"NOT_SUPPORTED: cache quote uses native AE bank; an independent AE receipt observer is required");
        Class<?> sink=Class.forName(PROBES+"$Sink");
        Object callback=Proxy.newProxyInstance(sink.getClassLoader(),new Class<?>[]{sink},(proxy,method,args)->{
            if(method.getName().equals("record")){observe((String)args[0],(String)args[1]);return null;}
            if(method.getName().equals("toString"))return "GTMB bounded read-only EU ledger";
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            throw new IllegalStateException("Unexpected native sink method "+method);
        });
        call(probes,"start",callback,1048578);active=true;nativeSession=get(probes,"session");
        require((Boolean)call(probes,"enabled")&&nativeSession!=null,"native probe did not start");
        for(UUID owner:owners.keySet())call(probes,"addOwner",owner);
        inspectOwners(false);
        before=balance();
    }

    private void freezeCacheMetadata() throws Exception {
        Object warnings,sources;
        try{warnings=get(session,"blueprintEnergyWarnings");sources=get(session,"blueprintIntrinsicSources");}
        catch(NoSuchFieldException absent){throw new IllegalStateException("Native EU ledger NOT_SUPPORTED: product has no typed cache warning/intrinsic-source plan metadata",absent);}
        require(warnings instanceof List<?> && sources instanceof List<?>,"invalid native cache plan metadata types");
        for(Object warning:(List<?>)warnings){require(warning instanceof String,"invalid native cache warning type");planWarnings.add((String)warning);}
        require(planWarnings.isEmpty(),"planned cache omissions are not accepted: "+planWarnings);
        for(Object source:(List<?>)sources){
            require(source instanceof String,"invalid native intrinsic source type");
            String value=(String)source;
            require(value.equals("EnderIO native creative capacitor bank")||value.equals("AE native creative energy provider"),"unknown native intrinsic source enum: "+value);
            intrinsicSources.add(value);
        }
    }

    private String noticeOutcome(String notice) {
        if(notice.isEmpty())return "NONE";
        // Exact producer token only: insufficient/omitted/composite notices can never match this branch.
        if(notice.equals("保留原生固有能源")&&!intrinsicSources.isEmpty())return "NATIVE_INTRINSIC_DEFAULTS";
        return "REJECTED";
    }

    private void quote() throws Exception {
        Object cells;
        try{cells=get(session,"blueprintEnergyCells");}
        catch(NoSuchFieldException absent){throw new IllegalStateException("Native EU ledger NOT_SUPPORTED: product has no per-cell cache quote API",absent);}
        Object context=get(session,"materialContext");
        require(context!=null,"actual current material context required for cache pricing");
        observeTransferRoute(context);
        Map<String,Integer> routes=new LinkedHashMap<String,Integer>();
        for(Object demand:(List<?>)cells){
            Object quote=call(context,"cachedEnergyQuote",demand);
            cacheEu=cacheEu.add((BigInteger)get(quote,"eu"));
            double ae=((Number)get(quote,"ae")).doubleValue();require(Double.isFinite(ae)&&ae>=0,"invalid native AE quote");
            cacheAe=cacheAe.add(BigDecimal.valueOf(ae));String route=String.valueOf(get(quote,"source"));
            routes.put(route,routes.containsKey(route)?routes.get(route)+1:1);
        }
        quoteSources=routes.toString();
    }

    public void tick() throws Exception {
        if(!active)return;
        require(Thread.currentThread()==thread,"route witness must run on observer server thread");
        observeTransferRoute(get(session,"materialContext"));
    }
    private void observeTransferRoute(Object context)throws Exception {
        // Authenticated actions intentionally invalidate the lazy context even when they only close a UI.
        // Its absence is an observation gap, not evidence that either an AE or EU transfer route was used.
        if(context==null){materialRouteContextAbsentSamples++;return;}
        Object bank=construct("com.miaokatze.gtmb.hologram.HologramEnergyNetwork",actor,get(context,"terminalKey"));
        materialAeRouteSeen|=(Boolean)call(bank,"aeAvailable");
        materialRouteObservedSamples++;
    }

    private void observe(String type,String detail) {
        // Called synchronously under the producer LOCK. No I/O, world access, bank call or mutation here.
        try {
            if(!type.equals("EU_COMMIT")&&!type.equals("EU_REJECT"))return;
            if(Thread.currentThread()!=thread)problem("callback ran on a different thread");
            require(detail.matches("signedEU=-?[0-9]+"),"invalid native transaction detail");
            BigInteger amount=new BigInteger(detail.substring(9));
            if(type.equals("EU_REJECT")){rejects++;return;}
            commits++;String category=classify(Thread.currentThread().getStackTrace(),amount);
            sums.put(category,sums.get(category).add(amount));counts.put(category,counts.get(category)+1);
            if(category.equals("UNKNOWN"))problem("unclassified native commit "+amount+" "+stack(Thread.currentThread().getStackTrace()));
        } catch(RuntimeException exception){problem("observer error "+exception);}
    }

    private static String classify(StackTraceElement[] frames,BigInteger amount) {
        if(frame(frames,"com.miaokatze.gtmb.hologram.HologramEnergyNetwork","refundEu"))return "TRANSACTION_REFUND";
        if(frame(frames,"com.miaokatze.gtmb.hologram.HologramMaterials$Transaction","takeCachedEnergy"))return "CACHE";
        if(frame(frames,"com.miaokatze.gtmb.hologram.HologramMaterials$Context","networkPower"))return "TRANSFER";
        if(frame(frames,"com.miaokatze.gtmb.hologram.HologramMaterials$Transaction","takeEnergy"))return "CONSTRUCTION";
        if(frame(frames,"com.miaokatze.gtmb.hologram.HologramMaterials$Transaction","rollbackExternal"))return "CONSTRUCTION_REFUND";
        if(frame(frames,"gregtech.api.metatileentity.implementations.MTEWirelessEnergy","tryFetchingEnergy"))return "NATIVE_HATCH";
        if(frame(frames,"gregtech.common.covers.CoverEnergyWireless","tryFetchingEnergy"))return "NATIVE_COVER";
        if(frame(frames,"com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase","onCoverRemoval"))return "NATIVE_COVER_REMOVAL";
        for(StackTraceElement f:frames)if(f.getClassName().startsWith("com.miaokatze.gtswn.common.covers.")
            &&(f.getMethodName().equals("refillFromNetwork")||f.getMethodName().equals("doCoverThings")||f.getMethodName().equals("onCoverRemoval")))return "NATIVE_COVER";
        if(amount.signum()>=0&&frame(frames,"gtmb.v0111.probe.CompositeWorldWitness","fundPlacement"))return "SUPPLY";
        return "UNKNOWN";
    }

    private void inspectOwners(boolean includeTarget) throws Exception {
        nativeEnergyCoverCount=0;
        for(Object tile:new ArrayList<Object>((List<?>)get(world,"field_147482_g"))){
            int x=(Integer)get(tile,"field_145851_c"),y=(Integer)get(tile,"field_145848_d"),z=(Integer)get(tile,"field_145849_e");
            if(!inside(x,y,z,source,40)&&!inside(x,y,z,bank,32)&&!(includeTarget&&inside(x,y,z,target,40)))continue;
            Object meta;try{meta=call(tile,"getMetaTileEntity");}catch(NoSuchMethodException notGt){continue;}
            boolean energy=meta!=null&&isSubclass(meta,"gregtech.api.metatileentity.implementations.MTEWirelessEnergy");
            for(int side=0;side<6;side++){
                Object direction=call(Class.forName("net.minecraftforge.common.util.ForgeDirection"),"getOrientation",side);
                // GT .205 returns the installed concrete Cover behavior itself, not the old CoverInfo wrapper.
                // Both the owned .205 JAR and source expose no separate getBehavior/getCoverBehavior method.
                Object cover=call(tile,"getCoverAtSide",direction);
                if(cover!=null&&(cover.getClass().getName().equals("com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless")
                    ||cover.getClass().getName().equals("com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless")
                    ||cover.getClass().getName().equals("gregtech.common.covers.CoverEnergyWireless"))){energy=true;nativeEnergyCoverCount++;}
            }
            if(!energy)continue;
            UUID owner=(UUID)call(tile,"getOwnerUuid");
            if(meta!=null&&isSubclass(meta,"gregtech.api.metatileentity.implementations.MTEWirelessEnergy")){
                UUID initialized=(UUID)get(meta,"owner_uuid");
                require(initialized==null||initialized.equals(owner),"native hatch runtime owner differs from tile owner");
            }
            UUID nativeLeader=leader(owner);
            require(leader.equals(nativeLeader),"NOT_SUPPORTED: source/bank/target native energy owner maps to another bank at "+x+":"+y+":"+z);
            require(!includeTarget||owners.containsKey(owner),"new native energy owner alias was not enrolled before placement: "+owner);
            owners.put(owner,nativeLeader);
            if(active)call(Class.forName(PROBES),"addPosition",x,y,z);
        }
    }

    public Object finish(BigInteger supplied) throws Exception {
        require(active&&!finished,"one active observation window required");
        Object out=construct("net.minecraft.nbt.NBTTagCompound");
        try {
            require(Thread.currentThread()==thread,"finish must run on observer server thread");
            require(get(Class.forName(PROBES),"session")==nativeSession&&(Boolean)call(Class.forName(PROBES),"enabled"),"global probe session changed");
            inspectOwners(true);
            observeTransferRoute(get(session,"materialContext"));
            BigInteger after=balance(),all=BigInteger.ZERO;for(BigInteger value:sums.values())all=all.add(value);
            Map<?,?> nativeCounts=(Map<?,?>)call(Class.forName(PROBES),"counts");
            long nativeCommits=nativeCount(nativeCounts,"EU_COMMIT"),nativeRejects=nativeCount(nativeCounts,"EU_REJECT");
            put(out,"bankBefore",before.toString());put(out,"bankAfter",after.toString());put(out,"bankDelta",after.subtract(before).toString());
            put(out,"nativeSignedSum",all.toString());put(out,"constructionQuoteEU",construction.toString());put(out,"cacheQuoteEU",cacheEu.toString());
            put(out,"cacheQuoteAE",cacheAe.toPlainString());put(out,"cacheQuoteRoutes",quoteSources);put(out,"ownerLeaderMap",owners.toString());
            String notice=(String)get(session,"blueprintEnergyNotice"),noticeOutcome=noticeOutcome(notice);
            put(out,"cacheNotice",notice);put(out,"cacheNoticeOutcome",noticeOutcome);
            put(out,"frozenPlanWarnings",planWarnings.toString());put(out,"frozenIntrinsicSources",intrinsicSources.toString());
            put(out,"intrinsicBoundary","exact chosen native creative block/material capability; no exemption for other demand on the same cell; all quoted cache EU still requires real payment");
            put(out,"nativeEnergyCoverCount",Integer.toString(nativeEnergyCoverCount));
            put(out,"materialTransferAE","NOT_OBSERVED: no native AE-fee observer; ordinary AE transfer fees are outside this EU-account settlement claim");
            put(out,"materialAeRouteSeen",materialRouteObservedSamples==0?"UNKNOWN":Boolean.toString(materialAeRouteSeen));
            put(out,"materialRouteObservedSamples",Long.toString(materialRouteObservedSamples));
            put(out,"materialRouteContextAbsentSamples",Long.toString(materialRouteContextAbsentSamples));
            put(out,"materialRouteCoverage",materialRouteObservedSamples==0?"NOT_OBSERVED":materialRouteContextAbsentSamples>0?"PARTIAL_CONTEXT_GAPS":"SAMPLED");
            put(out,"materialRouteObservationBoundary","null lazy context means UNKNOWN for that sample; no route is inferred or context constructed; samples do not prove native AE transfer-fee settlement");
            put(out,"nativeCommitCount",Long.toString(nativeCommits));put(out,"nativeRejectCount",Long.toString(nativeRejects));
            put(out,"observedCommitCount",Long.toString(commits));put(out,"observedRejectCount",Long.toString(rejects));
            for(String category:CATEGORIES){put(out,category+"SignedEU",sums.get(category).toString());put(out,category+"Count",counts.get(category).toString());}
            put(out,"claim","native EU-account settlement and per-cell production cache quote agreement; not independent cache-pricing correctness or AE-account settlement");
            put(out,"window","B0 after native observer enrollment, before finite placement supply; B1 after actual product placement terminal, before world postcheck; initial 100B already in B0");
            require(errors==0,"observer latched errors: "+failure);
            require(nativeCommits==commits&&nativeRejects==rejects,"native event counts disagree with callback counts");
            require(after.subtract(before).equals(all),"bank delta differs from all native committed transactions");
            require(sums.get("SUPPLY").equals(supplied),"native finite supply differs from recorded supply");
            require(sums.get("CONSTRUCTION").equals(construction.negate()),"native construction debit differs from complete placement quote");
            require(sums.get("CACHE").equals(cacheEu.negate()),"native cache debit differs from per-cell quote; empty-cache fallback is not accepted");
            require(!noticeOutcome.equals("REJECTED"),"product cache notice is not empty or an exact native-intrinsic token backed by frozen known sources; fallback/omission/migration is refused");
            require(sums.get("TRANSACTION_REFUND").signum()==0&&sums.get("CONSTRUCTION_REFUND").signum()==0,"complete placement requires no transaction refunds");
            require(sums.get("NATIVE_COVER_REMOVAL").signum()==0,"ordinary placement window unexpectedly refunded a removed native cover buffer");
            require(sums.get("TRANSFER").signum()<=0,"invalid positive material-transfer charge");
            require(rejects==0,"native rejected transactions observed; complete payment claim requires no rejection");
            put(out,"status","PASS");finished=true;return out;
        } catch(Exception exception){put(out,"status","FAIL");put(out,"failure",exception.toString());throw new LedgerFailure(out,exception);}
        finally{put(out,"observerErrors",Long.toString(errors));put(out,"diagnostics",diagnostics.toString());close();}
    }

    /** Carries a complete failure receipt without weakening the caller's existing assertions. */
    public static final class LedgerFailure extends Exception {
        public final Object receipt;
        LedgerFailure(Object receipt,Exception cause){super("Native EU ledger failed: "+cause,cause);this.receipt=receipt;}
    }
    public void close() throws Exception {
        if(!active)return;
        require(Thread.currentThread()==thread,"observer stop must run on its original server thread");
        require(get(Class.forName(PROBES),"session")==nativeSession,"refusing to stop another owner's probe session");
        call(Class.forName(PROBES),"stop");active=false;
        require(get(Class.forName(PROBES),"session")==null&&!(Boolean)call(Class.forName(PROBES),"enabled"),"native probe did not return to absent state");
    }
    private BigInteger balance()throws Exception{return (BigInteger)call(Class.forName("gregtech.common.misc.WirelessNetworkManager"),"getUserEU",actorOwner);}
    private static UUID leader(UUID owner)throws Exception{require(owner!=null,"native owner UUID is absent");Class<?> teams=Class.forName("gregtech.common.misc.spaceprojects.SpaceProjectManager");require((Boolean)call(teams,"isInTeam",owner),"native owner has no initialized team; observer will not create it");return (UUID)call(teams,"getLeader",owner);}
    private void problem(String message){errors++;if(failure.isEmpty())failure=message;if(diagnostics.size()<32)diagnostics.add(message);}
    private static boolean inside(int x,int y,int z,int[] origin,int size){return x>=origin[0]&&x<origin[0]+size&&y>=origin[1]&&y<origin[1]+size&&z>=origin[2]&&z<origin[2]+size;}
    private static boolean isSubclass(Object object,String name){for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())if(c.getName().equals(name))return true;return false;}
    private static boolean frame(StackTraceElement[] frames,String type,String method){for(StackTraceElement f:frames)if(f.getClassName().equals(type)&&f.getMethodName().equals(method))return true;return false;}
    private static String stack(StackTraceElement[] frames){StringBuilder out=new StringBuilder();for(int i=0;i<frames.length&&i<24;i++)out.append(frames[i]).append(';');return out.toString();}
    private static long nativeCount(Map<?,?> counts,String name){for(Map.Entry<?,?> e:counts.entrySet())if(e.getKey().toString().equals(name))return ((Number)e.getValue()).longValue();throw new IllegalStateException("native count type absent: "+name);}
    private static void put(Object out,String key,String value)throws Exception{call(out,"func_74778_a",key,value);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("Native EU ledger: "+message);}
    private static Object get(Object object,String name)throws Exception{Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object instanceof Class?null:object);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(type.getName()+"."+name);}
    private static Object construct(String name,Object... args)throws Exception{for(Constructor<?> c:Class.forName(name).getDeclaredConstructors())if(compatible(c.getParameterTypes(),args)){c.setAccessible(true);return c.newInstance(args);}throw new NoSuchMethodException(name);}
    private static Object call(Object object,String name,Object... args)throws Exception{Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(object instanceof Class?null:object,args);}throw new NoSuchMethodException(type.getName()+"."+name);}
    private static boolean compatible(Class<?>[] types,Object[] values){if(types.length!=values.length)return false;for(int i=0;i<types.length;i++){if(values[i]==null){if(types[i].isPrimitive())return false;continue;}Class<?> type=types[i];if(type.isPrimitive())type=type==int.class?Integer.class:type==long.class?Long.class:type==boolean.class?Boolean.class:type;if(!type.isInstance(values[i]))return false;}return true;}
}
