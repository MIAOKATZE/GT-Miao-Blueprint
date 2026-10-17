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

/**
 * The same test-only fixture witness is used with 0.1.9, 0.1.10 and the candidate.
 * It admits only the runner's fixed, copied, already-owned fixture world and never replaces a product class.
 * Production import/process/Save/payment/placement/raw operations still arrive from the connected client.
 */
public final class CompositeWorldWitness {
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

    public CompositeWorldWitness(Object world,Object actor,Object manifest,Object seedReceipt)throws Exception {
        this.world=world;this.actor=actor;this.manifest=manifest;this.source=ints(manifest,"source");this.bankOrigin=ints(manifest,"bank");result=nbt();
        check(Arrays.equals(ints(manifest,"size"),new int[]{40,40,40}),"immutable exact fixture dimensions");
        check((Integer)get(actor,"field_71093_bK")==integer(manifest,"dimension"),"actual connected actor dimension");
        check(!(Boolean)get(get(actor,"field_71075_bZ"),"field_75098_d"),"survival actor required");
        Object gameType=call(get(actor,"field_71134_c"),"func_73081_b");
        check((Integer)call(gameType,"func_77148_a")==0,"actual native GameType SURVIVAL(0)");
        putInt(result,"actualNativeGameType",0);
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
        call(inventory,"func_70299_a",0,construct("net.minecraft.item.ItemStack",projector));set(inventory,"field_70461_c",0);
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
        if(baselineCursor<64000){
            baselineLastWorldTick=(Long)call(world,"func_82737_E");
            if(baselineScanTicks++==0)baselineFirstWorldTick=baselineLastWorldTick;
            for(int n=0;n<256&&baselineCursor<64000;n++,baselineCursor++){
                Object row=read(source,baselineCursor);baseline.add(row);
                if(!text(row,"block").equals("minecraft:air"))sourceNonAirBlocks++;
                inspectPhysicalMinimum(row);staticSourceDigest.update(canonicalBytes(normalize(row,"raw",true)));
            }
            if(baselineCursor==64000){
                putInt(result,"sourceVolume",baselineCursor);putInt(result,"sourceNonAirBlocks",sourceNonAirBlocks);
                putInt(result,"sourceAirBlocks",baselineCursor-sourceNonAirBlocks);putInt(result,"expectedBlueprintCells",sourceNonAirBlocks);
                putText(result,"sourceFingerprintAlgorithm",SOURCE_FINGERPRINT_ALGORITHM);
                putText(result,"blueprintCountBasis","one independent native World baseline scan of all 64000 positions; vanilla air excluded from ordinary blueprint cells; raw includes air");
                putText(result,"sourceSha256",hash(baseline));StringBuilder value=new StringBuilder();for(byte b:staticSourceDigest.digest())value.append(String.format("%02x",b&255));putText(result,"staticSourceSha256",value.toString());putText(result,"status","READY");
            }
            return;
        }
        if(!checking.isEmpty()) {
            if(awaitingFormation){if(!formedTarget())return;awaitingFormation=false;}
            for(int n=0;n<256&&cursor<64000;n++,cursor++) {
                Object expected=normalize(baseline.get(cursor),checking,true),actual=normalize(read(target,cursor),checking,false);
                if(has(expected,"tile"))tiles++;
                if(!expected.equals(actual)&&mismatches.size()<16)mismatches.add(cursor+": expected="+abbreviate(expected)+" actual="+abbreviate(actual));
                if(checking.equals("raw")){Object sourceCell=read(source,cursor);if(text(sourceCell,"block").equals("minecraft:air")&&!has(sourceCell,"tile"))sourceAir++;}
            }
            if(cursor==64000){String kind=checking;checking="";putInt(result,kind+"ComparedCells",cursor);putInt(result,kind+"ComparedTiles",tiles);putInt(result,kind+"SourceAirCells",sourceAir);putText(result,kind+"Mismatches",mismatches.toString());putLong(result,kind+"PostcheckNanos",System.nanoTime()-operationStarted);check(mismatches.isEmpty(),"independent "+kind+" native data mismatch: "+mismatches);if(kind.equals("raw"))check(sourceAir==64000,"raw source independently empty at every selected cell");putBool(result,kind+"Passed",true);putText(result,"status","READY");}
        }
        observeSave();
    }

    public boolean ready(){return baselineCursor==64000&&checking.isEmpty();}
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
        int[] at=ints(manifest,kind);check((Integer)get(s,"x")==at[0]&&(Integer)get(s,"y")==at[1]&&(Integer)get(s,"z")==at[2],"exact canonical target");
        call(product("HologramResourcePlanner"),"refreshPlan",s);Object quote=get(s,"blueprintTotalResources");check(quote!=null,"actual server total quote");
        verifyPhysicalMinimum(quote,kind);
        Object proxy=call(drive,"getProxy"),storage=call(proxy,"getStorage");check((Boolean)call(proxy,"isActive"),"naturally active seeded finite grid");itemMonitor=call(storage,"getItemInventory");fluidMonitor=call(storage,"getFluidInventory");
        check(empty(itemMonitor)&&empty(fluidMonitor),"native finite bank begins empty");
        bankBefore=stockSnapshot();euBefore=(BigInteger)call(Class.forName("gregtech.common.misc.WirelessNetworkManager"),"getUserEU",call(actor,"func_110124_au"));wirelessBefore=wirelessStored();euQuote=(BigInteger)get(quote,"energy");
        if(Boolean.getBoolean(CompositeEuLedger.PROPERTY)){
            Object settings=get(s,"materialSettings");set(settings,"main",false);set(settings,"containers",false);set(settings,"me",true);
            call(product("HologramMaterials"),"write",call(actor,"func_70694_bm"),settings);
            set(s,"materialContext",construct("com.miaokatze.gtmb.hologram.HologramMaterials$Context",actor,settings));
            euLedger=new CompositeEuLedger(world,actor,s,source,bankOrigin,at,euQuote);euLedger.begin();
            putText(result,"nativeEuLedgerProfile","opt-in native GTSWN hook; extra GTSWN-TEST dimension/listeners; no native scenario or UI kit; not comparable to historical gate=false environment");
        }
        check(((List<?>)get(quote,"essentia")).isEmpty(),"no unsupported essentia fixture supply");
        Object modulate=enumValue("appeng.api.config.Actionable","MODULATE");
        for(Object item:(List<?>)get(quote,"items")){Object offered=call(Class.forName("appeng.util.item.AEItemStack"),"create",call(item,"func_77946_l"));check(call(itemMonitor,"injectItems",offered,modulate,actionSource)==null,"finite exact item supply accepted");stock.add(call(offered,"copy"));}
        for(Object cost:(List<?>)get(quote,"fluids")){Object offered=call(Class.forName("appeng.util.item.AEFluidStack"),"create",call(get(cost,"stack"),"copy"));call(offered,"setStackSize",get(cost,"amount"));check(call(fluidMonitor,"injectItems",offered,modulate,actionSource)==null,"finite exact fluid supply accepted");fluids.add(call(offered,"copy"));}
        if(euQuote.signum()>0){check((Boolean)call(Class.forName("gregtech.common.misc.WirelessNetworkManager"),"addEUToGlobalEnergyMap",call(actor,"func_110124_au"),euQuote),"finite exact EU supply accepted");fixtureEuCredits=fixtureEuCredits.add(euQuote);}
        Object settings=get(s,"materialSettings");set(settings,"main",false);set(settings,"containers",false);set(settings,"me",true);call(product("HologramMaterials"),"write",call(actor,"func_70694_bm"),settings);set(s,"materialContext",construct("com.miaokatze.gtmb.hologram.HologramMaterials$Context",actor,settings));
        call(product("HologramResourcePlanner"),"refreshPlan",s);call(product("HologramSnapshots"),"send",s);
        check((Integer)get(s,"planMissing")==0,"actual finite ME bank made complete plan payable: "+get(s,"status"));
        funded=kind;tag(result,kind+"FundedStock",stockSnapshot());putText(result,kind+"QuotedEU",euQuote.toString());
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
    private void startCheck(String kind)throws Exception{check(ready(),"one oracle operation");checking=kind;target=ints(manifest,kind);cursor=tiles=sourceAir=0;mismatches.clear();awaitingFormation=true;operationStarted=System.nanoTime();putText(result,"status","WAIT_NATIVE_FORMATION_"+kind);}
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
