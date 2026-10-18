package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;

/** Real AE combined regression. Reuses the completed crafting fixture's bank, terminal and native actor source. */
public final class HologramV0116AeQuoteAcceptanceSmoke implements AutoCloseable {

    private static final int CELLS = 50000, X = 1220, Y = 96, Z = 1220;
    private static final long DEMAND = 5_000_000_000L;
    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final HologramCompositeLabBank.Bank bank;
    private final IMEMonitor<IAEItemStack> monitor;
    private final PlayerSource nativeSource;
    private final NBTTagCompound receipt = new NBTTagCompound();
    private final ItemStack stone = new ItemStack(Blocks.stone), dirt = new ItemStack(Blocks.dirt);
    private final ItemStack alpha = variant("alpha"), beta = variant("beta"), decoration;
    private final ItemStack terminal;
    private final IAEItemPowerStorage terminalPower;
    private final IEnergyGrid gridEnergy;
    private final double oldX, oldY, oldZ;
    private final boolean oldCreative, oldMetrics;
    private final int oldSlot;
    private final long started;
    private final HologramSession session;
    private HologramMaterialQuote.Preparation quote;
    private CountingStock stock;
    private Object snapshot;
    private Method snapshotStep;
    private HologramCapture firstCapture;
    private HologramMaterialQuote.Cache firstCache;
    private long firstRevision, firstFullVisits, firstFullBuilds, lastTick = Long.MIN_VALUE;
    private int stage, quoteSteps, snapshotSteps, placementTicks;
    private boolean closed, finished;

    static HologramV0116AeQuoteAcceptanceSmoke create(WorldServer world, EntityPlayerMP actor,
        HologramCompositeLabBank.Bank bank, IMEMonitor<IAEItemStack> monitor, PlayerSource nativeSource)
        throws Exception {
        require(
            Boolean.getBoolean("gtmb.v0116.aeQuoteAcceptance"),
            "explicit combined native AE quote switch required");
        require(
            actor instanceof FakePlayer && actor.worldObj == world
                && !world.isRemote
                && world.getWorldInfo()
                    .getWorldName()
                    .matches("hologram-smoke-v0115-[A-Za-z0-9_-]{1,20}"),
            "owned native acceptance world and Forge actor required");
        require(
            !actor.capabilities.isCreativeMode && bank.ready(),
            "real survival actor and naturally ready bank required");
        require(
            monitor != null && monitor.getClass()
                .getName()
                .startsWith("appeng.") && !java.lang.reflect.Proxy.isProxyClass(monitor.getClass()),
            "actual native ME monitor required");
        return new HologramV0116AeQuoteAcceptanceSmoke(world, actor, bank, monitor, nativeSource);
    }

    private HologramV0116AeQuoteAcceptanceSmoke(WorldServer world, EntityPlayerMP actor,
        HologramCompositeLabBank.Bank bank, IMEMonitor<IAEItemStack> monitor, PlayerSource nativeSource)
        throws Exception {
        this.world = world;
        this.actor = actor;
        this.bank = bank;
        this.monitor = monitor;
        this.nativeSource = nativeSource;
        oldX = actor.posX;
        oldY = actor.posY;
        oldZ = actor.posZ;
        oldCreative = actor.capabilities.isCreativeMode;
        oldMetrics = HologramRuntimeMetrics.isEnabled();
        oldSlot = actor.inventory.currentItem;
        started = world.getTotalWorldTime();
        terminal = actor.inventory.mainInventory[34];
        require(
            terminal != null && terminal.getItem() instanceof IAEItemPowerStorage,
            "actual carried native terminal required");
        terminalPower = (IAEItemPowerStorage) terminal.getItem();
        gridEnergy = ((IGrid) bank.nativeGrid()).getCache(IEnergyGrid.class);
        require(gridEnergy != null, "actual native energy cache required");
        // One finite initial top-up, before every observation. No stock or power is supplied during work.
        double maximum = terminalPower.getAEMaxPower(terminal);
        require(Double.isFinite(maximum) && maximum > 0, "finite native terminal capacity required");
        terminalPower.injectAEPower(terminal, Math.max(0, maximum - terminalPower.getAECurrentPower(terminal)));
        Object registered = cpw.mods.fml.common.registry.GameRegistry.findItem("chisel", "factoryblock");
        require(registered instanceof net.minecraft.item.Item, "native Chisel item required");
        decoration = new ItemStack((net.minecraft.item.Item) registered, 1, 6);
        require(
            HologramDecorationVoucher.covered(Block.getBlockFromItem(decoration.getItem()), decoration),
            "actual configured native decoration voucher coverage required");
        seed(stone, CELLS);
        seed(alpha, 32);
        seed(beta, 17);
        seed(decoration, 32);
        seed(dirt, 16);
        actor.inventory.currentItem = 0;
        loadChunks();
        session = new HologramSession(actor, X, Y, Z, false, 1);
        session.virtual = session.virtualPlacement = session.virtualLocked = true;
        session.materialSettings.main = session.materialSettings.containers = false;
        session.materialSettings.me = true;
        session.virtualPreset = scene(CELLS);
        HologramBlueprintProjection.populateVirtual(session);
        require(
            session.capture.cells.size() == CELLS && !session.capture.incomplete,
            "complete native fifty-thousand-cell projection required");
        for (HologramCapture.Cell cell : session.capture.cells)
            require("pending".equals(cell.status), "fresh native scene must be pending");
        require(
            HologramMaterialAccess.materialSource(session)
                .craftingGrid() == bank.nativeGrid(),
            "production Context must authenticate the original carried terminal to the actual bank");
        nativeArithmeticProof();
        session.virtualPreset.deidentificationLevel = 1;
        session.virtualPreset.deidentificationChosen = true;
        NBTTagList rows = new NBTTagList();
        rows.appendTag(demand(stone, true, false));
        rows.appendTag(demand(alpha, false, false));
        rows.appendTag(demand(beta, false, false));
        rows.appendTag(demand(decoration, true, true));
        stock = new CountingStock(HologramMaterialQuote.stock(session));
        quote = new HologramMaterialQuote.Preparation(
            session,
            rows,
            HologramMaterialQuote.voucherRows(rows),
            new NBTTagList(),
            new HologramMaterialQuote.StockSnapshot(stock));
        receipt.setString("status", "LONG_QUOTE");
        receipt.setLong("longDemandPerRow", DEMAND);
        receipt.setInteger("nonAirCells", CELLS);
        receipt.setInteger("initialSupplyIdentities", 5);
        receipt.setInteger("stockRefillsDuringWork", 0);
        receipt.setDouble("terminalInitialFiniteAE", terminalPower.getAECurrentPower(terminal));
        receipt.setBoolean("survival", true);
        receipt.setBoolean("syntheticGrid", false);
        receipt.setBoolean("actualNetty", false);
        receipt.setBoolean("private48003SurvivalClaim", false);
        receipt.setString(
            "scope",
            "Actual existing AE crafting bank/terminal/PlayerSource; synthetic 50000 stone scene; exact-NBT variants quote; no user blueprint survival or Netty claim");
        receipt.setString(
            "stockQueryObservation",
            "Counting delegator at real Context stock entry; native terminal/ME monitor unchanged; not whole-server monitor instrumentation");
        receipt.setString(
            "schedulerBoundary",
            "Production job/snapshot slices once per natural END; disconnected FakePlayer workspace detached between callbacks; no connected workspace/Netty lifecycle claim");
        HologramRuntimeMetrics.setEnabled(true);
    }

    /** One callback per natural END tick; no manual native tile ticks, worker completions or inventory refills. */
    public NBTTagCompound poll() throws Exception {
        try {
            return pollSlice();
        } finally {
            // Ordinary WorkspaceScheduler retires disconnected FakePlayers and clears their work list.
            // This explicit native fixture drives real slices; leave no disconnected business slot between ticks.
            Field sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
            sessions.setAccessible(true);
            Map<?, ?> current = (Map<?, ?>) sessions.get(null);
            if (current.get(actor.getUniqueID()) == session) current.remove(actor.getUniqueID());
        }
    }

    private NBTTagCompound pollSlice() throws Exception {
        require(!closed, "combined native fixture is closed");
        if (finished) return (NBTTagCompound) receipt.copy();
        long now = world.getTotalWorldTime();
        require(
            now != lastTick && now - started < 14000,
            "one natural tick and finite combined fixture deadline required");
        lastTick = now;
        loadChunks();
        require(
            bank.ready() && HologramMaterialAccess.materialSource(session)
                .craftingGrid() == bank.nativeGrid(),
            "original authenticated native AE network must remain available");
        HologramWorkspace.activate(actor.getUniqueID(), session);
        if (stage == 0) {
            Observation before = observe();
            boolean done = quote.step(1, System.nanoTime() + 8_000_000L);
            unchanged(before);
            quoteSteps++;
            if (!done) return progress();
            verifyLongQuote();
            session.virtualPreset.deidentificationLevel = 0;
            HologramResourcePlanner.refreshPlan(session);
            NBTTagCompound metrics = HologramRuntimeMetrics.snapshot()
                .getCompoundTag("queues");
            firstFullVisits = metrics.getLong("fullCellsSnapshotCells");
            firstFullBuilds = metrics.getLong("fullCellsSnapshotBuilds");
            beginSnapshot();
            stage = 1;
        } else if (stage == 1 || stage == 3) {
            Observation before = observe();
            snapshotStep.invoke(snapshot, System.nanoTime() + 8_000_000L, (LongSupplier) System::nanoTime);
            unchanged(before);
            snapshotSteps++;
            if (!((Boolean) field(snapshot, "encoding"))) return progress();
            NBTTagCompound state = (NBTTagCompound) field(snapshot, "state");
            Object preparation = field(snapshot, "materialQuote");
            require(
                preparation instanceof HologramMaterialQuote.Preparation
                    && ((HologramMaterialQuote.Preparation) preparation).done(),
                "actual staged inventory quote must complete");
            require(
                !state.getBoolean("quotesIncomplete") && !state.getBoolean("planIncomplete")
                    && state.getLong("planRevision") == session.planRevision
                    && HologramMaterialQuote.cached(session),
                "complete actual snapshot/cache owner and revision required");
            NBTTagList materials = state.getTagList("materials", 10);
            require(
                materials.tagCount() == 1 && materials.getCompoundTagAt(0)
                    .getInteger("mainAvailable") == 0
                    && materials.getCompoundTagAt(0)
                        .getInteger("controllerAvailable") == -1,
                "legacy material fields must remain separate from the native AE quote");
            if (stage == 1) {
                require(
                    state.getTagList("cells", 10)
                        .tagCount() == CELLS && available(session.materialQuote, stone) == CELLS,
                    "large actual AE-connected full snapshot demand/stock required");
                firstCapture = session.capture;
                firstCache = session.materialQuoteCache;
                firstRevision = session.planRevision;
                receipt.setBoolean("largeSnapshotComplete", true);
                receipt.setBoolean("stagedQuotePreparationComplete", true);
                HologramPlacementJobs.start(session);
                require(
                    session.job == 1 && session.work.size() == CELLS && session.planMissing == 0,
                    "real production survival placement must admit all fifty thousand supplied stone cells");
                HologramSnapshotPreparation.cancel(session);
                stage = 2;
            } else {
                require(
                    session.capture != firstCapture && session.planRevision > firstRevision
                        && !firstCache.current(session),
                    "previous capture/revision cache must be invalid after terminal transition");
                require(
                    state.getTagList("cells", 10)
                        .tagCount() == 1 && available(session.materialQuote, dirt) == 1,
                    "replacement capture snapshot must contain its own new demand and actual ME observation");
                NBTTagCompound metrics = HologramRuntimeMetrics.snapshot()
                    .getCompoundTag("queues");
                receipt.setLong("fullCellsVisited", metrics.getLong("fullCellsSnapshotCells") - firstFullVisits);
                receipt.setLong("fullSnapshotBuilds", metrics.getLong("fullCellsSnapshotBuilds") - firstFullBuilds);
                require(
                    receipt.getLong("fullCellsVisited") == CELLS + 1L && receipt.getLong("fullSnapshotBuilds") == 2,
                    "exactly two complete real preparation baselines required");
                receipt.setBoolean("postTerminalCaptureSnapshotComplete", true);
                receipt.setBoolean("previousCaptureCacheRejected", true);
                receipt.setString("status", "PASS");
                finished = true;
            }
        } else if (stage == 2) {
            HologramPlacementJobs.tickJob(session);
            HologramSnapshotPreparation.cancel(session); // Disconnected FakePlayer business delivery is outside this
                                                         // test.
            placementTicks++;
            require(session.job == 1 || session.job == 3, "native survival job paused/stopped with unresolved cells");
            if (session.job != 3) return progress();
            require(
                session.completed == CELLS && session.cursor == CELLS
                    && remaining(stone) == 0
                    && remaining(alpha) == 32
                    && remaining(beta) == 17
                    && remaining(decoration) == 32,
                "native survival placement must debit exactly supplied plain stone and preserve distinct NBT stock");
            for (HologramCapture.Cell cell : session.capture.cells) require(
                "placed".equals(cell.status) && world.getBlock(cell.x, cell.y, cell.z) == Blocks.stone,
                "every real survival destination must contain its native placed stone");
            receipt.setBoolean("survivalPlacementComplete", true);
            receipt.setInteger("placedCells", session.completed);
            receipt.setLong("stoneDebit", CELLS);
            session.virtualPreset = scene(1);
            session.virtualPreset.cells.get(0).item = "minecraft:dirt";
            session.virtualPreset.cells.get(0).block = "minecraft:dirt";
            session.virtualPreset.cells.get(0).y = 30;
            HologramBlueprintProjection.populateVirtual(session);
            require(
                session.capture != firstCapture && session.planRevision > firstRevision,
                "new capture and native revised plan required");
            beginSnapshot();
            stage = 3;
        }
        return progress();
    }

    private void verifyLongQuote() {
        require(HologramMaterialQuote.cached(session), "long quote must publish a real session-owned cache");
        NBTTagCompound ordinary = session.materialQuoteCache.ordinary, voucher = session.materialQuoteCache.voucher;
        for (ItemStack identity : new ItemStack[] { stone, alpha, beta, decoration }) require(
            rowLong(ordinary, identity, "required") == DEMAND,
            "ordinary native quote must preserve the full five-billion long demand");
        for (ItemStack identity : new ItemStack[] { stone, alpha, beta }) require(
            rowLong(voucher, identity, "required") == DEMAND,
            "voucher native quote must preserve unwaived five-billion long demand");
        require(
            available(ordinary, stone) == CELLS && available(ordinary, alpha) == 32
                && available(ordinary, beta) == 17
                && available(ordinary, decoration) == 32,
            "normal quote must allocate actual positive ME stock and protect exact NBT variants");
        require(
            available(voucher, stone) == CELLS && available(voucher, alpha) == 32
                && available(voucher, beta) == 17
                && available(voucher, decoration) == 0,
            "voucher quote must share the same native stock observation and waive only decoration");
        require(
            stock.exactPositive > 0 && stock.fuzzyPositive > 0
                && stock.maximum > Integer.MAX_VALUE
                && stock.maximum < Long.MAX_VALUE,
            "native positive-stock exact and fuzzy long paths must both be exercised");
        for (int count : stock.calls.values())
            require(count == 1, "ordinary/voucher must not repeat a native Context stock query");
        receipt.setBoolean("longExactAndFuzzyPositive", true);
        receipt.setBoolean("boundedDemandQueries", true);
        receipt.setBoolean("normalVoucherSharedObservation", true);
        receipt.setBoolean("exactNbtVariantsSeparated", true);
        receipt.setInteger("nativeStockContextQueries", stock.totalCalls);
        receipt.setLong("maximumStockRequest", stock.maximum);
        receipt.setBoolean("quoteStockAndPowerUnchanged", true);
    }

    /** Actual AE Platform proof belongs inside FML. The spies only count and delegate to this live bank. */
    @SuppressWarnings("unchecked")
    private void nativeArithmeticProof() {
        int[] powerCalls = { 0 }, storageCalls = { 0 };
        IEnergySource energy = (amount, mode, multiplier) -> {
            require(
                mode == Actionable.SIMULATE && multiplier == PowerMultiplier.CONFIG,
                "native arithmetic proof must simulate real grid power with the actual fee multiplier");
            powerCalls[0]++;
            return gridEnergy.extractAEPower(amount, mode, multiplier);
        };
        IMEInventory<IAEItemStack> inventory = (IMEInventory<IAEItemStack>) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { IMEInventory.class },
            (proxy, method, args) -> {
                if ("extractItems".equals(method.getName())) {
                    require(
                        args[1] == Actionable.SIMULATE && args[2] == nativeSource,
                        "actual native PlayerSource and simulated monitor extraction required");
                    IAEItemStack request = (IAEItemStack) args[0];
                    require(
                        ItemStack.areItemStackTagsEqual(request.getItemStack(), alpha),
                        "native arithmetic requests must preserve the exact fixture NBT variant");
                    storageCalls[0]++;
                    return monitor.extractItems(request, Actionable.SIMULATE, (BaseActionSource) args[2]);
                }
                try {
                    return method.invoke(monitor, args);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        Observation before = observe();
        IAEItemStack request = AEItemStack.create(alpha.copy());
        request.setStackSize(Long.MAX_VALUE);
        boolean oldOverflow = false;
        try {
            appeng.util.Platform.poweredExtraction(energy, inventory, request, nativeSource, Actionable.SIMULATE);
        } catch (ArithmeticException expected) {
            oldOverflow = true;
        }
        require(
            oldOverflow && powerCalls[0] == 0 && storageCalls[0] == 0,
            "real loaded AE Platform must reproduce MAX ceilDiv overflow before energy/storage callbacks");
        unchanged(before);
        receipt.setBoolean("nativePlatformMaxOverflow", true);
        receipt.setBoolean("nativePlatformOverflowBeforeCallbacks", true);
        receipt.setInteger("nativeOverflowPowerCallbacks", powerCalls[0]);
        receipt.setInteger("nativeOverflowStorageCallbacks", storageCalls[0]);
        IAEItemStack safeMax = HologramMaterials.simulatePoweredExtraction(energy, inventory, request, nativeSource);
        require(
            safeMax != null && safeMax.getStackSize() == remaining(alpha)
                && safeMax.getStackSize() == 32
                && ItemStack.areItemStackTagsEqual(safeMax.getItemStack(), alpha),
            "safe bridge MAX request must observe actual thirty-two-item native NBT stock");
        require(
            powerCalls[0] == 1 && storageCalls[0] == 1,
            "safe MAX bridge must perform one real power/storage simulation");
        unchanged(before);
        request.setStackSize(DEMAND);
        IAEItemStack nativeFiveBillion = appeng.util.Platform
            .poweredExtraction(energy, inventory, request, nativeSource, Actionable.SIMULATE);
        request.setStackSize(DEMAND);
        IAEItemStack safeFiveBillion = HologramMaterials
            .simulatePoweredExtraction(energy, inventory, request, nativeSource);
        require(
            nativeFiveBillion != null && safeFiveBillion != null
                && nativeFiveBillion.getStackSize() == 32
                && nativeFiveBillion.getStackSize() == safeFiveBillion.getStackSize()
                && ItemStack.areItemStackTagsEqual(nativeFiveBillion.getItemStack(), safeFiveBillion.getItemStack()),
            "native five-billion demand and safe bridge must agree on actual finite NBT stock");
        require(
            powerCalls[0] == 3 && storageCalls[0] == 3,
            "each native/safe arithmetic comparison must simulate once");
        unchanged(before);
        receipt.setBoolean("nativeSafeMaxPreservesNbtStock", true);
        receipt.setBoolean("nativeFiveBillionBridgeMatches", true);
        receipt.setBoolean("nativeArithmeticNoDebit", true);
        receipt.setLong("nativeSafeMaxObserved", safeMax.getStackSize());
        receipt.setLong("nativeFiveBillionObserved", nativeFiveBillion.getStackSize());
        receipt.setInteger("nativeArithmeticPowerCallbacks", powerCalls[0]);
        receipt.setInteger("nativeArithmeticStorageCallbacks", storageCalls[0]);
    }

    private void beginSnapshot() throws Exception {
        HologramWorkspace.activate(actor.getUniqueID(), session);
        HologramNetwork.SnapshotTicket ticket = HologramNetwork.reserveOwnedState(session);
        require(ticket != null, "real snapshot reservation required");
        HologramNetwork.cancelState(ticket);
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSnapshotPreparation$Task");
        Constructor<?> constructor = type
            .getDeclaredConstructor(HologramSession.class, HologramNetwork.SnapshotTicket.class);
        constructor.setAccessible(true);
        snapshot = constructor.newInstance(session, ticket);
        snapshotStep = type.getDeclaredMethod("step", long.class, LongSupplier.class);
        snapshotStep.setAccessible(true);
    }

    private NBTTagCompound progress() {
        receipt.setInteger("combinedStage", stage);
        receipt.setInteger("longQuoteSteps", quoteSteps);
        receipt.setInteger("snapshotSteps", snapshotSteps);
        receipt.setInteger("placementTicks", placementTicks);
        receipt.setLong("naturalWorldTicks", world.getTotalWorldTime() - started);
        if (!finished) receipt.setString(
            "status",
            stage == 0 ? "LONG_QUOTE" : stage == 2 ? "SURVIVAL_PLACEMENT" : "SNAPSHOT_PREPARATION");
        return (NBTTagCompound) receipt.copy();
    }

    private void loadChunks() {
        for (int cx = (X - 1) >> 4; cx <= (X + 49) >> 4; cx++)
            for (int cz = (Z - 1) >> 4; cz <= (Z + 49) >> 4; cz++) world.theChunkProviderServer.loadChunk(cx, cz);
        actor.setPosition(oldX, oldY, oldZ); // Original real WAP/terminal range and source permissions remain
                                             // authoritative.
    }

    private void seed(ItemStack item, long quantity) {
        require(remaining(item) == 0, "combined fixture material identity must start empty");
        IAEItemStack offered = AEItemStack.create(item.copy());
        offered.setStackSize(quantity);
        require(
            monitor.injectItems(offered, Actionable.MODULATE, nativeSource) == null && remaining(item) == quantity,
            "one finite real-ME initial supply must be accepted exactly");
    }

    private long remaining(ItemStack item) {
        IAEItemStack stored = monitor.getStorageList()
            .findPrecise(AEItemStack.create(item));
        return stored == null ? 0 : stored.getStackSize();
    }

    private Observation observe() {
        return new Observation(
            new long[] { remaining(stone), remaining(alpha), remaining(beta), remaining(decoration), remaining(dirt) },
            gridEnergy.extractAEPower(1_000_000_000, Actionable.SIMULATE, PowerMultiplier.ONE),
            bank.fixturePowerReceipt()
                .getDouble("nativeCurrentAE"),
            terminalPower.getAECurrentPower(terminal));
    }

    private void unchanged(Observation before) {
        Observation after = observe();
        require(
            java.util.Arrays.equals(before.stock, after.stock),
            "quote/snapshot preparation must not debit ME inventory");
        require(
            Math.abs(before.grid - after.grid) < 0.000001 && Math.abs(before.cell - after.cell) < 0.000001
                && Math.abs(before.terminal - after.terminal) < 0.000001,
            "same-callback quote/snapshot preparation must not debit real grid/cell/terminal power");
    }

    private static NBTTagCompound demand(ItemStack identity, boolean shell, boolean covered) {
        NBTTagCompound row = identity.writeToNBT(new NBTTagCompound());
        row.setLong("required", DEMAND);
        row.setLong(shell ? "shellRequired" : "exactRequired", DEMAND);
        if (covered) row.setLong("voucherShellRequired", DEMAND);
        return row;
    }

    private static long available(NBTTagCompound quote, ItemStack item) {
        return rowLong(quote, item, "available");
    }

    private static long rowLong(NBTTagCompound quote, ItemStack item, String quantity) {
        NBTTagList rows = quote.getTagList("rows", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            ItemStack identity = HologramMaterialQuote.readItem(row);
            if (identity != null && identity.isItemEqual(item) && ItemStack.areItemStackTagsEqual(identity, item))
                return row.getLong(quantity);
        }
        return 0;
    }

    private static ItemStack variant(String variant) {
        ItemStack result = new ItemStack(Blocks.stone);
        result.setTagCompound(new NBTTagCompound());
        result.getTagCompound()
            .setString("gtmbNativeQuoteVariant", variant);
        return result;
    }

    private static HologramPresets.Preset scene(int cells) {
        HologramPresets.Preset result = new HologramPresets.Preset();
        result.schema = 2;
        result.id = "native-ae-combined-" + cells;
        result.name = "Native AE combined synthetic stone scene";
        result.controllerType = "area";
        result.retainNbt = true;
        for (int i = 0; i < cells; i++) {
            HologramPresets.Cell cell = HologramPresets
                .cell(new ItemStack(Blocks.stone), i % 50, i / 2500, i / 50 % 50, -1, false);
            cell.block = "minecraft:stone";
            result.cells.add(cell);
        }
        return result;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static final class Observation {

        final long[] stock;
        final double grid, cell, terminal;

        Observation(long[] stock, double grid, double cell, double terminal) {
            this.stock = stock;
            this.grid = grid;
            this.cell = cell;
            this.terminal = terminal;
        }
    }

    private static final class CountingStock implements HologramMaterialQuote.ItemStock {

        final HologramMaterialQuote.ItemStock actual;
        final Map<String, Integer> calls = new LinkedHashMap<>();
        int totalCalls, exactPositive, fuzzyPositive;
        long maximum;

        CountingStock(HologramMaterialQuote.ItemStock actual) {
            this.actual = actual;
        }

        public long exact(ItemStack item, long limit) {
            return count(item, limit, false);
        }

        public long fuzzy(ItemStack item, long limit) {
            return count(item, limit, true);
        }

        private long count(ItemStack item, long limit, boolean fuzzy) {
            require(
                limit > 0 && limit < Long.MAX_VALUE,
                "actual native query must use finite demand, never Long.MAX_VALUE");
            // ExactKey.hashCode alone is insufficient for identity. Native serialized fixture variants are tiny.
            String key = (fuzzy ? "fuzzy:" : "exact:") + item.getItemDamage()
                + ":"
                + net.minecraft.item.Item.itemRegistry.getNameForObject(item.getItem())
                + ":"
                + (item.hasTagCompound() ? item.getTagCompound()
                    .toString() : "");
            calls.merge(key, 1, Integer::sum);
            totalCalls++;
            maximum = Math.max(maximum, limit);
            long found = fuzzy ? actual.fuzzy(item, limit) : actual.exact(item, limit);
            if (found > 0) {
                if (fuzzy) fuzzyPositive++;
                else exactPositive++;
            }
            return found;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (snapshot != null) {
                Object metadata = field(snapshot, "metadata");
                if (metadata instanceof HologramSnapshotMetadataTask)
                    ((HologramSnapshotMetadataTask) metadata).cancel();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("combined native snapshot cleanup failed", failure);
        } finally {
            HologramSnapshotPreparation.cancel(session);
            HologramWorkspace.clear();
            actor.capabilities.isCreativeMode = oldCreative;
            actor.inventory.currentItem = oldSlot;
            actor.setPosition(oldX, oldY, oldZ);
            HologramRuntimeMetrics.setEnabled(oldMetrics);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("v0116 native AE quote: " + message);
    }
}
