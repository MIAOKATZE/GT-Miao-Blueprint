// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.register.ProjectorRegistry;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/**
 * Server-thread fixture administration and passive audits. Body construction/replacement/removal is never done here.
 */
public final class HologramFourMachineWorldFixture {

    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final NBTTagList originalInventory;
    private final int originalSlot, toolSlot;
    private final double originalX, originalY, originalZ;
    private final float originalYaw, originalPitch;
    private final int x, y = 80, z;
    private final List<int[]> actorFloor = new ArrayList<>();
    private HologramFourMachineAeBank bank;
    private final Path recoveryFile;
    private boolean prepared;
    private boolean bankAttempted;
    private HologramFourMachineWorldWrites writes;
    private int controllerStages;
    private boolean mutationStarted, prepareBlocked;
    private final List<int[]> sentinels = new ArrayList<>();
    private boolean closed;
    private int inventorySupply;
    private NBTTagCompound lastDropOracle = new NBTTagCompound();
    private HologramFourMachineRewardPreparation rewardPreparation;

    public HologramFourMachineWorldFixture(WorldServer world, EntityPlayerMP actor, UUID expectedActor,
        Path recoveryFile) throws Exception {
        require(
            world != null && actor != null && actor.worldObj == world && expectedActor.equals(actor.getUniqueID()),
            "Wrong actual fixture actor");
        require(
            actor.theItemInWorldManager.getGameType() == GameType.SURVIVAL && !actor.capabilities.isCreativeMode
                && !actor.canCommandSenderUseCommand(2, "miaotest"),
            "Business actor must be real non-OP survival");
        require(
            Boolean.TRUE.equals(
                Class.forName("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy")
                    .getMethod("owns", net.minecraft.world.World.class)
                    .invoke(null, world)),
            "Exact owned save policy required");
        this.world = world;
        this.actor = actor;
        this.recoveryFile = recoveryFile;
        originalInventory = (NBTTagList) actor.inventory.writeToNBT(new NBTTagList())
            .copy();
        originalSlot = actor.inventory.currentItem;
        originalX = actor.posX;
        originalY = actor.posY;
        originalZ = actor.posZ;
        originalYaw = actor.rotationYaw;
        originalPitch = actor.rotationPitch;
        int empty = -1;
        for (int index = 0; index < 9; index++) if (actor.inventory.getStackInSlot(index) == null) {
            empty = index;
            break;
        }
        require(
            empty >= 0 && actor.inventory.getStackInSlot(34) == null,
            "Owned projector and terminal slots unavailable");
        toolSlot = empty;
        x = ((int) Math.floor(actor.posX) & ~15) + 8;
        z = ((int) Math.floor(actor.posZ) & ~15) + 8;
        NBTTagCompound ledger = new NBTTagCompound();
        ledger.setString("actor", expectedActor.toString());
        ledger.setTag("inventory", originalInventory.copy());
        ledger.setInteger("selectedSlot", originalSlot);
        ledger.setDouble("x", originalX);
        ledger.setDouble("y", originalY);
        ledger.setDouble("z", originalZ);
        ledger.setFloat("yaw", originalYaw);
        ledger.setFloat("pitch", originalPitch);
        ledger.setIntArray("anchor", anchor());
        ledger.setString(
            "ownedSave",
            world.getSaveHandler()
                .getWorldDirectory()
                .getAbsolutePath());
        Files.write(
            recoveryFile,
            ledger.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW);
    }

    /** Snapshot object and on-disk ledger exist before the first mutation, including partial prepare failures. */
    public void prepare(boolean withAe) throws Exception {
        require(!prepared && !closed, "Fixture already prepared");
        NBTTagCompound admission = recoveryState();
        if (!admission.getBoolean("clear")) {
            prepareBlocked = true;
            Path blocked = recoveryFile.resolveSibling(recoveryFile.getFileName() + ".prepare-blocked");
            Files.write(
                blocked,
                admission.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
            throw new IllegalStateException(
                "PREPARE_BLOCKED: preexisting native recovery/work; actor inventory and AE have not been borrowed: "
                    + admission);
        }
        for (int[] p : new int[][] { { x + 11, y, z + 11 }, { x - 11, y, z - 11 } }) {
            require(world.isAirBlock(p[0], p[1], p[2]), "Owned sentinel position was not empty");
            sentinels.add(p);
            mutationStarted = true;
            world.setBlock(p[0], p[1], p[2], Blocks.gold_block, 0, 3);
        }
        actor.inventory.setInventorySlotContents(toolSlot, new ItemStack(ProjectorRegistry.projector));
        actor.inventory.currentItem = toolSlot;
        actor.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(toolSlot));
        actor.inventoryContainer.detectAndSendChanges();
        if (withAe) {
            for (int[] p : bankPositions())
                require(world.isAirBlock(p[0], p[1], p[2]), "Owned AE footprint was not empty");
            bankAttempted = true;
            bank = new HologramFourMachineAeBank(world, actor, x + 14, y, z - 10, 34);
        }
        writes = new HologramFourMachineWorldWrites(world, anchor());
        prepared = true;
    }

    /** Called only from the owned server's END tick, after the normal production scheduler. */
    public void tick(WorldServer current) {
        if (closed) return;
        require(current == world && actor.worldObj == world, "Four-machine fixture world changed");
        for (int count = 0; count < 2; count++) {
            Runnable command = commands.poll();
            if (command == null) return;
            command.run();
        }
    }

    public int[] anchor() {
        return new int[] { x, y, z };
    }

    public int toolSlot() {
        return toolSlot;
    }

    public UUID actorId() {
        return actor.getUniqueID();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean preparationBlocked() {
        return prepareBlocked;
    }

    public boolean settleControllerReward() throws Exception {
        if (rewardPreparation == null) rewardPreparation = new HologramFourMachineRewardPreparation(world, actor);
        return rewardPreparation.tick(this);
    }

    public NBTTagCompound rewardPreparationReceipt() {
        return rewardPreparation == null ? new NBTTagCompound() : rewardPreparation.receipt();
    }

    public <T> CompletableFuture<T> request(Callable<T> action) {
        require(!closed && commands.size() < 8, "Fixture command queue unavailable");
        CompletableFuture<T> result = new CompletableFuture<>();
        commands.add(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    public boolean bankReady() throws Exception {
        return bank.ready();
    }

    public NBTTagCompound controller() {
        return HologramFourMachineWorldAudit.cell(world, x, y, z);
    }

    public NBTTagList sentinels() {
        NBTTagList result = new NBTTagList();
        for (int[] p : sentinels) {
            require(
                world.getBlock(p[0], p[1], p[2]) == Blocks.gold_block,
                "Native operation escaped actual footprint and changed sentinel");
            result.appendTag(HologramFourMachineWorldAudit.cell(world, p[0], p[1], p[2]));
        }
        return result;
    }

    public void watch(int[][] positions, String action) {
        writes.arm(positions, action);
    }

    public NBTTagCompound worldWrites() {
        return writes.snapshot();
    }

    public NBTTagCompound stageController(HologramFourMachineSpec spec, ExtendedFacing facing) {
        require(world.isAirBlock(x, y, z), "Previous controller has not been physically removed");
        for (int[] p : actorFloor) world.setBlockToAir(p[0], p[1], p[2]);
        actorFloor.clear();
        int fx = facing.getDirection().offsetX * 4, fz = facing.getDirection().offsetZ * 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int[] p = { x + fx + dx, y - 1, z + fz + dz };
            require(world.isAirBlock(p[0], p[1], p[2]), "Owned standing platform is not empty");
            actorFloor.add(p);
            world.setBlock(p[0], p[1], p[2], Blocks.stone, 0, 3);
        }
        actor.setPositionAndUpdate(x + fx + .5, y, z + fz + .5);
        if (controllerStages++ > 0) {
            actor.inventory.setInventorySlotContents(toolSlot, new ItemStack(ProjectorRegistry.projector));
            actor.inventory.currentItem = toolSlot;
            actor.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(toolSlot));
            actor.inventoryContainer.detectAndSendChanges();
        }
        ItemStack item = spec.registeredController();
        require(
            ((ItemMachines) item.getItem()).placeBlockAt(
                item,
                actor,
                world,
                x,
                y,
                z,
                facing.getDirection()
                    .ordinal(),
                .5F,
                .5F,
                .5F,
                0),
            "Native controller item refused fixture placement");
        require(world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity, "Native controller tile missing");
        BaseMetaTileEntity base = (BaseMetaTileEntity) world.getTileEntity(x, y, z);
        require(
            base.getMetaTileID() == spec.controllerId && spec.className.equals(
                base.getMetaTileEntity()
                    .getClass()
                    .getName()),
            "Fixture controller differs from live registry");
        base.setOwnerName(actor.getCommandSenderName());
        base.setOwnerUuid(actor.getUniqueID());
        base.setFrontFacing(facing.getDirection());
        require(base.getMetaTileEntity() instanceof IAlignment, "Native extended facing unavailable");
        ((IAlignment) base.getMetaTileEntity()).setExtendedFacing(facing);
        base.disableWorking();
        world.markBlockForUpdate(x, y, z);
        return HologramFourMachineWorldAudit.cell(world, x, y, z);
    }

    /** Once per explicit test phase. Items go through real slots or the native AE monitor, never a result field. */
    public void supply(List<ItemStack> stacks, boolean ae) throws Exception {
        if (ae) {
            bank.supply(stacks);
            return;
        }
        for (ItemStack source : stacks) {
            require(source != null && source.stackSize > 0, "Invalid finite inventory supply");
            inventorySupply = Math.addExact(inventorySupply, source.stackSize);
            require(inventorySupply <= HologramFourMachineSpec.MAX_SEEDED_ITEMS, "Finite inventory fixture exhausted");
            ItemStack remainder = source.copy();
            require(
                actor.inventory.addItemStackToInventory(remainder) && remainder.stackSize == 0,
                "Finite fixture stock did not fit the actual business inventory");
        }
        actor.inventoryContainer.detectAndSendChanges();
    }

    public NBTTagCompound resources() throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        result.setLong("worldTick", world.getTotalWorldTime());
        result
            .setTag("inventory", HologramFourMachineWorldAudit.counts(HologramFourMachineWorldAudit.inventory(actor)));
        result.setTag(
            "looseItems",
            HologramFourMachineWorldAudit.counts(HologramFourMachineWorldAudit.looseItems(world, x, y, z)));
        result.setTag("looseEntityDetails", HologramFourMachineWorldAudit.looseEntities(world, x, z));
        if (bank != null) result.setTag("ae", bank.snapshot());
        result.setInteger("selectedSlot", actor.inventory.currentItem);
        result.setInteger("fixtureInventorySupplied", inventorySupply);
        result.setInteger("fixtureControllersSupplied", controllerStages);
        result.setTag("lastDropOracle", lastDropOracle.copy());
        result.setTag("rewardPreparation", rewardPreparationReceipt());
        result.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        result.setString(
            "gameType",
            actor.theItemInWorldManager.getGameType()
                .name());
        result.setBoolean("actorCreative", actor.capabilities.isCreativeMode);
        result.setBoolean("actorCanOp2", actor.canCommandSenderUseCommand(2, "miaotest"));
        result.setString(
            "worldProvider",
            world.provider.getClass()
                .getName());
        result.setInteger("dimension", actor.dimension);
        return result;
    }

    /** Independent native callbacks run on isolated full NBT clones, before any real demolition request. */
    public NBTTagCompound expectedDrops(int[][] positions) throws Exception {
        require(positions.length <= HologramFourMachineSpec.MAX_TARGET_CELLS + 1, "Drop oracle bound exceeded");
        NBTTagCompound before = resources();
        java.util.Map<NBTTagCompound, Long> counts = new java.util.LinkedHashMap<>();
        NBTTagList cells = new NBTTagList();
        try {
            for (int[] p : positions) {
                require(
                    p.length == 3 && Math.abs(p[0] - x) <= 16 && Math.abs(p[1] - y) <= 12 && Math.abs(p[2] - z) <= 16,
                    "Drop oracle escaped owned footprint");
                HologramFourMachineDropOracle.Quote quote = HologramFourMachineDropOracle.inspect(world, p);
                for (ItemStack drop : quote.items) HologramFourMachineWorldAudit.add(counts, drop);
                cells.appendTag(quote.evidence);
            }
        } finally {
            require(before.equals(resources()), "Isolated drop oracle changed live inventory, AE or loose items");
        }
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("items", HologramFourMachineWorldAudit.counts(counts));
        result.setTag("cells", cells);
        result.setBoolean("liveResourcesUnchanged", true);
        result.setString(
            "source",
            "Deep NBT isolated native getDrops + breakBlock EntityItems, fortune=0, incl controller");
        lastDropOracle = (NBTTagCompound) result.copy();
        return result;
    }

    public NBTTagList cells(int[][] positions) {
        require(positions.length <= HologramFourMachineSpec.MAX_TARGET_CELLS, "Audit footprint exceeded its bound");
        NBTTagList result = new NBTTagList();
        for (int[] p : positions) {
            require(
                p.length == 3 && Math.abs(p[0] - x) <= 16 && Math.abs(p[1] - y) <= 12 && Math.abs(p[2] - z) <= 16,
                "Audit escaped reviewed owned platform");
            result.appendTag(HologramFourMachineWorldAudit.cell(world, p[0], p[1], p[2]));
        }
        return result;
    }

    /** Deliberate owned fixture damage. Never counted as BUILD/REPAIR/REPLACE/DEMOLISH work. */
    public NBTTagCompound damage(int[] missing, int[] wrong) {
        for (int[] p : new int[][] { missing, wrong }) {
            require(
                p.length == 3 && Math.abs(p[0] - x) <= 12
                    && Math.abs(p[1] - y) <= 10
                    && Math.abs(p[2] - z) <= 12
                    && world.getTileEntity(p[0], p[1], p[2]) == null,
                "Damage must target reviewed non-TE body cells");
            require(!world.isAirBlock(p[0], p[1], p[2]), "Damage position was not a real built cell");
        }
        NBTTagCompound before = new NBTTagCompound();
        before.setTag("missingBefore", HologramFourMachineWorldAudit.cell(world, missing[0], missing[1], missing[2]));
        before.setTag("wrongBefore", HologramFourMachineWorldAudit.cell(world, wrong[0], wrong[1], wrong[2]));
        require(world.func_147480_a(missing[0], missing[1], missing[2], true), "Owned native break failed");
        require(world.func_147480_a(wrong[0], wrong[1], wrong[2], true), "Owned native mismatch break failed");
        require(
            world.setBlock(wrong[0], wrong[1], wrong[2], Blocks.cobblestone, 0, 3),
            "Owned mismatch fixture failed");
        before.setTag("fixtureSuppliedMismatch", new ItemStack(Blocks.cobblestone).writeToNBT(new NBTTagCompound()));
        before.setString("purpose", "fixture-only damage; original native drops remain in the real world ledger");
        return before;
    }

    public NBTTagCompound restore() throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagCompound recovery = recoveryState();
        result.setTag("recoveryGuard", recovery);
        result.setBoolean("fixtureMutationStarted", mutationStarted);
        result.setBoolean("prepareBlocked", prepareBlocked);
        if (!recovery.getBoolean("clear")) {
            result.setBoolean("cleanupPending", true);
            result.setBoolean("inventoryAndSlotRestored", false);
            result.setBoolean("temporaryAeNetworkRemoved", false);
            result.setString(
                "recoveryEntry",
                "/gtmb recovery via console/OP2; inspect retained native journal and delivery receipts before settlement");
            result.setString("actorSnapshot", recoveryFile.toString());
            Path pending = recoveryFile.resolveSibling(recoveryFile.getFileName() + ".pending");
            if (!Files.exists(pending)) Files.write(
                pending,
                result.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
            return result; // No bank, world block, actor slot/inventory or recovery marker has been changed.
        }
        if (!mutationStarted) {
            require(
                originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList()))
                    && originalSlot == actor.inventory.currentItem,
                "Unmutated fixture actor's original inventory changed externally");
            result.setBoolean("originalStateUnchanged", true);
            result.setBoolean("restorationRequired", false);
            result.setBoolean("cleanupPending", false);
            closed = true;
            return result;
        }
        try {
            result.setTag("beforeCleanup", resources());
        } catch (Exception unavailable) {
            result.setString("beforeCleanupAuditFailure", unavailable.toString());
        }
        if (writes != null) {
            result.setTag("worldWrites", writes.snapshot());
            writes.close();
        }
        if (bank != null) bank.removeOwned();
        // The exact owned AE footprint is predeclared so partial constructor failures are recoverable too.
        if (bankAttempted) for (int[] p : bankPositions()) world.setBlockToAir(p[0], p[1], p[2]);
        for (int[] p : actorFloor) world.setBlockToAir(p[0], p[1], p[2]);
        for (int[] p : sentinels) world.setBlockToAir(p[0], p[1], p[2]);
        actor.inventory.readFromNBT((NBTTagList) originalInventory.copy());
        actor.inventory.currentItem = originalSlot;
        actor.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(originalSlot));
        actor.setPositionAndUpdate(originalX, originalY, originalZ);
        actor.rotationYaw = originalYaw;
        actor.rotationPitch = originalPitch;
        actor.inventoryContainer.detectAndSendChanges();
        require(
            originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList()))
                && originalSlot == actor.inventory.currentItem,
            "Complete fixture inventory restoration failed");
        require(
            actor.posX == originalX && actor.posY == originalY
                && actor.posZ == originalZ
                && actor.rotationYaw == originalYaw
                && actor.rotationPitch == originalPitch,
            "Complete fixture actor pose restoration failed");
        result.setBoolean("inventoryAndSlotRestored", true);
        result.setBoolean("cleanupPending", false);
        result.setBoolean("positionAndRotationRestored", true);
        result.setBoolean("temporaryAeNetworkRemoved", true);
        result.setTag("originalInventory", originalInventory.copy());
        Files.write(
            recoveryFile.resolveSibling(recoveryFile.getFileName() + ".restored"),
            result.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW);
        closed = true;
        return result;
    }

    /**
     * Real server-side authority, journal, active owner and persistent delivery receipts; never a client Flow guess.
     */
    public NBTTagCompound recoveryState() {
        NBTTagCompound result = new NBTTagCompound();
        boolean journal = HologramConstructionJournal.pending(actor);
        boolean delivery = HologramMaterials.Context.pendingWorldRecovery(actor);
        boolean known = HologramConstructionAuthority.hasKnownRecovery(actor);
        HologramSession session = HologramWorkspace.find(actor.getUniqueID());
        boolean owned = session != null && HologramNetwork.stockNativeOwns(session);
        boolean local = HologramNativeLocalConstruction.active(actor), demolition = HologramDemolition.active(actor);
        result.setBoolean("journalPending", journal);
        result.setBoolean("deliveryPending", delivery);
        result.setBoolean("knownRecovery", known);
        result.setBoolean("serverNativeOwner", owned);
        result.setBoolean("serverLocalConstruction", local);
        result.setBoolean("serverDemolition", demolition);
        result.setBoolean("clear", !journal && !delivery && !known && !owned && !local && !demolition);
        result.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        result.setString(
            "ownedSave",
            world.getSaveHandler()
                .getWorldDirectory()
                .getAbsolutePath());
        result.setString(
            "journalFile",
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .resolve("gtmb-construction-recovery")
                .resolve(actor.getUniqueID() + ".nbt")
                .toString());
        NBTTagCompound persisted = actor.getEntityData()
            .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG),
            retained = new NBTTagCompound();
        for (Object entry : persisted.func_150296_c()) {
            String key = (String) entry;
            if (key.startsWith("gtmb")) retained.setTag(
                key,
                persisted.getTag(key)
                    .copy());
        }
        result.setTag("nativePersistedRecovery", retained);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private int[][] bankPositions() {
        return new int[][] { { x + 14, y, z - 10 }, { x + 13, y, z - 10 }, { x + 13, y + 1, z - 10 },
            { x + 13, y - 1, z - 10 } };
    }
}
