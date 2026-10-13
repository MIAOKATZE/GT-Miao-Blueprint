package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

/** Opt-in real registered-machine capture and shell construction in a fresh, isolated server world. */
public final class HologramCompatibilitySmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-COMPAT] ";
    private int ticks, assertions;
    private boolean finished;

    /** Main isolated smoke also exercises the current official GT5U machine without optional external mods. */
    static void runIndustrialElectrolyzer(WorldServer world) throws Exception {
        if (!world.getWorldInfo()
            .getWorldName()
            .contains("hologram-smoke"))
            throw new AssertionError("industrial electrolyzer fixture requires isolated smoke world");
        HologramCompatibilitySmoke fixture = new HologramCompatibilitySmoke();
        FakePlayer player = FakePlayerFactory.get(
            world,
            new GameProfile(UUID.fromString("87e2020f-3fe4-4fc5-8539-a09fe34c80e7"), "GTMBElectrolyzerSmoke"));
        EmbeddedChannel transport = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        NetworkManager manager = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields()) if (field.getType() == Channel.class) {
            field.setAccessible(true);
            field.set(manager, transport);
        }
        player.playerNetServerHandler = new NetHandlerPlayServer(MinecraftServer.getServer(), manager, player);
        try {
            fixture.machine(
                world,
                player,
                "gregtech.common.tileentities.machines.multi.MTEIndustrialElectrolyzer",
                160,
                new String[0],
                new int[0]);
            fixture.machine(
                world,
                player,
                "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace",
                192,
                new String[] { "coil" },
                new int[] { 3 });
            fixture.machine(
                world,
                player,
                "gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor",
                224,
                new String[0],
                new int[0]);
            System.out.println("[GTMB-HOLOGRAM-ELECTROLYZER-SMOKE] FINAL PASS assertions=" + fixture.assertions);
        } finally {
            transport.finish();
            Object pending;
            while ((pending = transport.readOutbound()) != null) io.netty.util.ReferenceCountUtil.release(pending);
            HologramService.clear();
        }
    }

    static void registerIfEnabled() {
        if (Boolean.getBoolean("gtmb.hologram.compatibilitySmoke")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramCompatibilitySmoke());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END || ++ticks < 40) return;
        finished = true;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            run(server.worldServerForDimension(0));
            System.out.println(
                PREFIX + "FINAL PASS assertions="
                    + assertions
                    + " real registered machines; survival shell construction; formation not checked");
        } catch (Throwable failure) {
            System.err.println(PREFIX + "FINAL FAIL " + failure);
            failure.printStackTrace();
        } finally {
            HologramService.clear();
            server.initiateShutdown();
        }
    }

    private void run(WorldServer world) throws Exception {
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-compat-"),
            "fresh_world_guard");
        ItemStack probe = new ItemStack(net.minecraft.init.Items.feather, 7);
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(this);
        HologramChannelTrace.Report report;
        try {
            ChannelDataAccessor.getChannelData(probe, "compat_probe");
        } finally {
            report = HologramChannelTrace.end(scope);
        }
        require(report.observations.containsKey("compat_probe"), "actual_accessor_mixin_injected");
        require(report.observations.get("compat_probe").effective == 7, "actual_accessor_master_fallback");
        FakePlayer player = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("a2b5ac19-10f6-4daf-93cd-e188a29041f6"), "GTMBCompatSmoke"));
        EmbeddedChannel transport = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        NetworkManager manager = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields()) if (field.getType() == Channel.class) {
            field.setAccessible(true);
            field.set(manager, transport);
        }
        player.playerNetServerHandler = new NetHandlerPlayServer(MinecraftServer.getServer(), manager, player);
        try {
            machine(
                world,
                player,
                "goodgenerator.blocks.tileEntity.MTEPreciseAssembler",
                48,
                new String[] { "unit_casing", "machine_casing", "glass" },
                new int[] { 3, 5, 2 });
            machine(
                world,
                player,
                "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.processing.MTEIndustrialAlloySmelter",
                80,
                new String[] { "coil" },
                new int[] { 4 });
            machine(
                world,
                player,
                "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace",
                112,
                new String[0],
                new int[0]);
        } finally {
            transport.finish();
            Object pending;
            while ((pending = transport.readOutbound()) != null) io.netty.util.ReferenceCountUtil.release(pending);
        }
    }

    private void machine(WorldServer world, FakePlayer player, String type, int x, String[] channels, int[] values)
        throws Exception {
        int id = -1;
        for (int i = 1; i < GregTechAPI.METATILEENTITIES.length; i++) {
            if (GregTechAPI.METATILEENTITIES[i] != null && type.equals(
                GregTechAPI.METATILEENTITIES[i].getClass()
                    .getName())) {
                id = i;
                break;
            }
        }
        require(id > 0, type + "_registered");
        for (int xx = x - 12; xx <= x + 12; xx++) for (int zz = 36; zz <= 60; zz++) {
            world.getChunkFromBlockCoords(xx, zz);
            for (int yy = 68; yy <= 92; yy++)
                if (!world.isAirBlock(xx, yy, zz)) throw new AssertionError("fixture must be fresh empty world");
        }
        require(true, type + "_fresh_fixture_air");
        world.setBlock(x, 80, 48, GregTechAPI.sBlockMachines, 0, 3);
        BaseMetaTileEntity base = (BaseMetaTileEntity) world.getTileEntity(x, 80, 48);
        base.setInitialValuesAsNBT(null, (short) id);
        base.setFrontFacing(ForgeDirection.NORTH);
        base.setOwnerName(player.getCommandSenderName());
        base.setOwnerUuid(player.getUniqueID());
        MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) base.getMetaTileEntity();
        Item projector = (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
        ItemStack inherited = new ItemStack(projector);
        HologramCapture first = collect(machine, inherited);
        require(first.channelReport != null, type + "_real_mixin_report");
        for (String channel : channels)
            require(first.channelReport.observations.containsKey(channel), type + "_observed_" + channel);
        ItemStack signal = inherited.copy();
        for (int i = 0; i < channels.length; i++) ChannelDataAccessor.setChannelData(signal, channels[i], values[i]);
        HologramCapture selected = collect(machine, signal);
        boolean electrolyzer = type.equals("gregtech.common.tileentities.machines.multi.MTEIndustrialElectrolyzer");
        if (electrolyzer) {
            require((Integer) get(machine, "casingAmount") == 0, "electrolyzer_capture_does_not_count_casings");
            int casings = 0, brass = 0, tin = 0, frames = 0;
            ItemStack frame = gtPlusPlus.core.material.MaterialsAlloy.POTIN.getFrameBox(1);
            for (HologramCapture.Cell cell : selected.cells) {
                if (cell.anchor) continue;
                if (cell.block == gregtech.api.casing.Casings.ElectrolyzerCasing.getBlock()
                    && cell.meta == gregtech.api.casing.Casings.ElectrolyzerCasing.getBlockMeta()) casings++;
                if (cell.block == gregtech.api.casing.Casings.BrassItemPipeCasing.getBlock()
                    && cell.meta == gregtech.api.casing.Casings.BrassItemPipeCasing.getBlockMeta()) brass++;
                if (cell.block == gregtech.api.casing.Casings.TinItemPipeCasing.getBlock()
                    && cell.meta == gregtech.api.casing.Casings.TinItemPipeCasing.getBlockMeta()) tin++;
                if (cell.block == net.minecraft.block.Block.getBlockFromItem(frame.getItem())
                    && cell.meta == frame.getItemDamage()) frames++;
                HologramDeclarationCandidates declared = new HologramDeclarationCandidates();
                declared.collect(cell.element, machine, world, cell.x, cell.y, cell.z, signal, null, 0);
                require(
                    declared.stacks.stream()
                        .anyMatch(
                            stack -> stack.getItem() == new ItemStack(cell.block).getItem()
                                && stack.getItemDamage() == cell.meta),
                    "electrolyzer_declared_material_includes_every_shell_cell");
            }
            require(
                casings == 48 && brass == 4 && tin == 4 && frames == 12,
                "electrolyzer_full_real_declaration_48_casings_8_pipes_12_frames");
        }
        for (int i = 0; i < channels.length; i++) {
            ItemStack one = inherited.copy();
            ChannelDataAccessor.setChannelData(one, channels[i], values[i]);
            HologramCapture isolated = collect(machine, one);
            boolean changedChannel = false;
            for (HologramCapture.Cell cell : isolated.cells) {
                if (cell.anchor) continue;
                HologramCapture.Cell old = first.positions.get(cell.x + "," + cell.y + "," + cell.z);
                if (old == null || cell.block == old.block && cell.meta == old.meta) continue;
                require(
                    HologramDeclarationWrappers.channels(cell.element)
                        .contains(channels[i]),
                    type + "_signal_does_not_change_other_components_" + channels[i]);
                changedChannel = true;
            }
            require(changedChannel, type + "_single_channel_changes_declared_component_" + channels[i]);
        }
        int changed = 0;
        java.util.Map<String, ItemStack> mandatoryHatches = new java.util.LinkedHashMap<>();
        for (HologramCapture.Cell cell : selected.cells) {
            if (cell.anchor || cell.block == net.minecraft.init.Blocks.air) continue;
            if (cell.unknown) mandatoryHatches.put(position(cell), requiredHatch(machine, world, cell, signal, type));
            else require(cell.block != null, type + "_known_declared_target");
            HologramCapture.Cell old = first.positions.get(cell.x + "," + cell.y + "," + cell.z);
            if (old != null && (cell.block != old.block || cell.meta != old.meta)) changed++;
        }
        require(channels.length == 0 || changed > 0, type + "_independent_signal_changes_targets");
        HologramService.clear();
        player.inventory.mainInventory = new ItemStack[36];
        player.inventory.mainInventory[0] = signal.copy();
        player.inventory.currentItem = 0;
        player.setPosition(x + .5, 80, 46.5);
        player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        player.capabilities.allowEdit = true;
        HologramService.open(player, x, 80, 48, 2);
        Object session = session(player);
        settleCapture(world, session);
        NBTTagCompound configure = action(session, "configure");
        configure.setInteger("main", 1);
        configure.setTag(
            "channels",
            signal.getTagCompound() == null ? new NBTTagCompound()
                : signal.getTagCompound()
                    .getCompoundTag("channels")
                    .copy());
        configure.setBoolean("noHatches", true);
        configure.setInteger("mode", 0);
        configure.setInteger("scope", 0);
        configure.setInteger("layer", 0);
        configure.setInteger("selected", -1);
        configure.setInteger(
            "facing",
            machine.getExtendedFacing()
                .ordinal());
        HologramService.handle(player, configure);
        settleCapture(world, session);
        for (Map.Entry<String, ItemStack> entry : mandatoryHatches.entrySet()) {
            HologramCapture current = (HologramCapture) get(session, "capture");
            HologramCapture.Cell cell = current.positions.get(entry.getKey());
            require(
                cell != null && cell.unknown && "hatch".equals(cell.role),
                type + "_required_hatch_retains_hint_identity");
            player.inventory.mainInventory[35] = entry.getValue()
                .copy();
            NBTTagCompound pin = action(session, "customPin");
            pin.setInteger("selected", current.cells.indexOf(cell));
            pin.setInteger("inventorySlot", 35);
            HologramService.handle(player, pin);
            player.inventory.mainInventory[35] = null;
            ItemStack target = ((java.util.List<ItemStack>) get(session, "planTargets"))
                .get(current.cells.indexOf(cell));
            require(
                target != null && target.isItemEqual(entry.getValue()),
                type + "_required_hatch_server_pin_accepted");
        }
        repeatedMaterialGrades(world, player, session, type, configure);
        HologramCapture planned = (HologramCapture) get(session, "capture");
        require(!planned.incomplete && !planned.pending, type + "_service_complete_capture");
        int slot = 1, targets = 0;
        java.util.LinkedHashMap<String, ItemStack> materials = new java.util.LinkedHashMap<>();
        java.util.Map<String, ItemStack> plannedSamples = new java.util.LinkedHashMap<>();
        for (int index = 0; index < planned.cells.size(); index++) {
            HologramCapture.Cell cell = planned.cells.get(index);
            if (cell.anchor || cell.block == net.minecraft.init.Blocks.air) continue;
            ItemStack stack;
            if (mandatoryHatches.containsKey(position(cell))) {
                stack = ((java.util.List<ItemStack>) get(session, "planTargets")).get(index)
                    .copy();
                require(
                    stack.isItemEqual(mandatoryHatches.get(position(cell))),
                    type + "_required_hatch_plan_preserved");
            } else {
                require(!cell.unknown && cell.block != null, type + "_service_known_shell");
                stack = new ItemStack(cell.block, 1, cell.meta);
            }
            require(stack.getItem() != null, type + "_registered_shell_item");
            plannedSamples.put(position(cell), stack.copy());
            if (electrolyzer && HologramFrameSupport.inertFrameBlock(cell.block, cell.meta)) {
                require(
                    cell.block.hasTileEntity(cell.meta) && world.getTileEntity(cell.x, cell.y, cell.z) == null,
                    "electrolyzer_real_GTPP_frame_is_nominal_container_without_tile");
                require(
                    cell.candidates != null && cell.candidates.stream()
                        .anyMatch(
                            candidate -> candidate.getItem() == stack.getItem()
                                && candidate.getItemDamage() == stack.getItemDamage()),
                    "electrolyzer_no_hatches_candidates_keep_real_GTPP_frames");
            }
            String key = Item.itemRegistry.getNameForObject(stack.getItem()) + ":" + stack.getItemDamage();
            if (materials.containsKey(key)) materials.get(key).stackSize++;
            else materials.put(key, stack);
            targets++;
        }
        for (ItemStack stack : materials.values()) while (stack.stackSize > 0) {
            require(slot < 36, "material_fixture_inventory_capacity");
            ItemStack batch = stack.copy();
            batch.stackSize = Math.min(stack.stackSize, stack.getMaxStackSize());
            stack.stackSize -= batch.stackSize;
            player.inventory.mainInventory[slot++] = batch;
        }
        HologramService.handle(player, action(session, "build"));
        for (int attempt = 0; attempt < 128
            && ((Integer) get(session, "job") == 1 || (Boolean) get(session, "capturePending")); attempt++) {
            world.getWorldInfo()
                .incrementTotalWorldTime(world.getTotalWorldTime() + 20);
            HologramService.tick();
        }
        require((Integer) get(session, "job") != 1, type + "_build_completes");
        HologramCapture completed = (HologramCapture) get(session, "capture");
        for (HologramCapture.Cell cell : planned.cells) {
            if (cell.anchor || cell.block == net.minecraft.init.Blocks.air) continue;
            if (plannedMatch(world, cell, plannedSamples.get(position(cell)))) continue;
            HologramCapture.Cell result = completed.positions.get(cell.x + "," + cell.y + "," + cell.z);
            System.err.println(
                PREFIX + "UNPLACED pos="
                    + cell.x
                    + ","
                    + cell.y
                    + ","
                    + cell.z
                    + " want="
                    + net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block)
                    + ":"
                    + cell.meta
                    + " actual="
                    + net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(cell.x, cell.y, cell.z))
                    + ":"
                    + world.getBlockMetadata(cell.x, cell.y, cell.z)
                    + " status="
                    + (result == null ? "missingCell" : result.status)
                    + " role="
                    + cell.role
                    + " nominalTile="
                    + cell.block.hasTileEntity(cell.meta)
                    + " declaration="
                    + cell.element.getClass()
                        .getName()
                    + " candidates="
                    + cell.candidates);
        }
        int placed = 0;
        for (HologramCapture.Cell cell : planned.cells) {
            if (cell.anchor || cell.block == net.minecraft.init.Blocks.air) continue;
            require(
                plannedMatch(world, cell, plannedSamples.get(position(cell))),
                type + "_survival_shell_placed_"
                    + cell.x
                    + "_"
                    + cell.y
                    + "_"
                    + cell.z
                    + " status="
                    + get(session, "status"));
            placed++;
        }
        require(world.getTileEntity(x, 80, 48) == base, type + "_controller_preserved");
        int remaining = 0;
        for (int i = 1; i < 36; i++)
            if (player.inventory.mainInventory[i] != null) remaining += player.inventory.mainInventory[i].stackSize;
        require(remaining == 0 && placed == targets, type + "_materials_consumed_once");
        if (electrolyzer) {
            require((Integer) get(machine, "casingAmount") == 0, "electrolyzer_service_capture_does_not_run_counter");
            NBTTagCompound remove = (NBTTagCompound) configure.copy();
            remove.setInteger("mode", 2);
            remove.setInteger("scope", 0);
            remove.setInteger(
                "facing",
                machine.getExtendedFacing()
                    .ordinal());
            HologramService.handle(player, remove);
            settleCapture(world, session);
            NBTTagCompound start = action(session, "start");
            start.setLong("planRevision", (Long) get(session, "planRevision"));
            HologramService.handle(player, start);
            for (int attempt = 0; attempt < 128 && (Integer) get(session, "job") == 1; attempt++) {
                world.getWorldInfo()
                    .incrementTotalWorldTime(world.getTotalWorldTime() + 20);
                HologramService.tick();
            }
            require((Integer) get(session, "job") != 1, "electrolyzer_dismantle_completes");
            java.util.Map<String, Integer> expected = new java.util.LinkedHashMap<>();
            for (HologramCapture.Cell cell : planned.cells) {
                if (cell.anchor || cell.block == net.minecraft.init.Blocks.air) continue;
                require(world.isAirBlock(cell.x, cell.y, cell.z), "electrolyzer_dismantles_casing_frame_and_pipe");
                ItemStack material = new ItemStack(cell.block, 1, cell.meta);
                String key = Item.itemRegistry.getNameForObject(material.getItem()) + ":" + material.getItemDamage();
                expected.merge(key, 1, Integer::sum);
            }
            java.util.Map<String, Integer> recovered = new java.util.LinkedHashMap<>();
            for (int i = 1; i < 36; i++) {
                ItemStack material = player.inventory.mainInventory[i];
                if (material == null) continue;
                String key = Item.itemRegistry.getNameForObject(material.getItem()) + ":" + material.getItemDamage();
                recovered.merge(key, material.stackSize, Integer::sum);
            }
            require(expected.equals(recovered), "electrolyzer_dismantle_recovers_each_declared_material_exactly_once");
            require(world.getTileEntity(x, 80, 48) == base, "electrolyzer_dismantle_preserves_controller");
        }
        System.out.println(
            PREFIX + "MACHINE PASS id="
                + id
                + " type="
                + type
                + " cells="
                + planned.cells.size()
                + " placed="
                + placed
                + " channelChanges="
                + changed);
    }

    private static String position(HologramCapture.Cell cell) {
        return cell.x + "," + cell.y + "," + cell.z;
    }

    private ItemStack requiredHatch(MTEEnhancedMultiBlockBase<?> machine, WorldServer world, HologramCapture.Cell cell,
        ItemStack trigger, String type) {
        require("hatch".equals(cell.role), type + "_unknown_position_is_required_hatch_declaration");
        HologramDeclarationCandidates declaration = new HologramDeclarationCandidates();
        ItemStack enabled = trigger.copy();
        ChannelDataAccessor.setChannelData(enabled, "gt_hatch", 1);
        declaration.collect(cell.element, machine, world, cell.x, cell.y, cell.z, enabled, null, 0);
        require(declaration.complete && !declaration.stacks.isEmpty(), type + "_required_hatch_declaration_complete");
        ItemStack result = null;
        int tier = Integer.MAX_VALUE;
        for (ItemStack stack : declaration.stacks) {
            IMetaTileEntity prototype = gregtech.common.blocks.ItemMachines.getMetaTileEntity(stack);
            require(
                prototype instanceof gregtech.api.metatileentity.implementations.MTEHatch,
                type + "_required_hatch_candidate_is_registered_hatch");
            int candidateTier = ((gregtech.api.metatileentity.implementations.MTEHatch) prototype).mTier;
            if (candidateTier < tier) {
                result = stack.copy();
                tier = candidateTier;
            }
        }
        require(result != null, type + "_required_hatch_has_safe_native_material");
        return result;
    }

    private static boolean plannedMatch(WorldServer world, HologramCapture.Cell cell, ItemStack sample) {
        if (sample != null && sample.getItem() instanceof gregtech.common.blocks.ItemMachines) {
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            return tile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) tile).getMetaTileID() == sample.getItemDamage();
        }
        return world.getBlock(cell.x, cell.y, cell.z) == cell.block
            && world.getBlockMetadata(cell.x, cell.y, cell.z) == cell.meta;
    }

    private void repeatedMaterialGrades(WorldServer world, FakePlayer player, Object session, String type,
        NBTTagCompound initial) throws Exception {
        HologramCapture capture = (HologramCapture) get(session, "capture");
        int selected = -1;
        for (int index = 0; index < capture.cells.size(); index++) if (!capture.cells.get(index).family.isEmpty()) {
            selected = index;
            break;
        }
        if (selected < 0) return;
        HologramCapture.Cell source = capture.cells.get(selected);
        HologramCapabilities.Result capabilities = (HologramCapabilities.Result) get(session, "capabilities");
        net.minecraft.nbt.NBTTagList rows = capabilities.toNBT();
        NBTTagCompound row = null;
        java.util.List<NBTTagCompound> options = new java.util.ArrayList<>();
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound candidate = rows.getCompoundTagAt(index);
            if (candidate.getString("id")
                .equals("main")) continue;
            java.util.List<NBTTagCompound> matches = new java.util.ArrayList<>();
            net.minecraft.nbt.NBTTagList values = candidate.getTagList("options", 10);
            for (int value = 0; value < values.tagCount(); value++) {
                NBTTagCompound option = values.getCompoundTagAt(value);
                ItemStack stack = ItemStack.loadItemStackFromNBT(option.getCompoundTag("item"));
                if (option.getBoolean("present") && stack != null
                    && source.candidates.stream()
                        .anyMatch(allowed -> allowed.isItemEqual(stack)))
                    matches.add(option);
            }
            if (matches.size() >= 3) {
                row = candidate;
                options = matches;
                break;
            }
        }
        if (row == null) return;
        String position = source.x + "," + source.y + "," + source.z;
        int[] steps = { 0, 1, 2, 1, 0, 1, 2 };
        boolean firstStep = true;
        for (int step : steps) {
            // Real GUI gestures arrive on later ticks; retain the production eight-action flood boundary.
            world.getWorldInfo()
                .incrementTotalWorldTime(world.getTotalWorldTime() + 1);
            NBTTagCompound request = (NBTTagCompound) initial.copy();
            request.getCompoundTag("channels")
                .setInteger(
                    row.getString("id"),
                    options.get(step)
                        .getInteger("value"));
            request.setInteger("selected", firstStep ? selected : (Integer) get(session, "selected"));
            firstStep = false;
            request.setIntArray("selectedIndices", new int[] { request.getInteger("selected") });
            HologramService.handle(player, request);
            require(
                ((NBTTagCompound) get(session, "channels")).getInteger(row.getString("id")) == options.get(step)
                    .getInteger("value"),
                type + "_grade_request_accepted_by_server");
            settleCapture(world, session);
            capture = (HologramCapture) get(session, "capture");
            HologramCapture.Cell changed = capture.cells.get((Integer) get(session, "selected"));
            require(position.equals(changed.x + "," + changed.y + "," + changed.z), type + "_grade_keeps_selection");
            require(source.family.equals(changed.family), type + "_grade_keeps_declaration_family");
            ItemStack expected = ItemStack.loadItemStackFromNBT(
                options.get(step)
                    .getCompoundTag("item"));
            require(
                changed.block == net.minecraft.block.Block.getBlockFromItem(expected.getItem())
                    && changed.meta == expected.getItem()
                        .getMetadata(expected.getItemDamage()),
                type + "_grade_repeated_plus_minus_reaches_native_capture");
            require(world.isAirBlock(changed.x, changed.y, changed.z), type + "_grade_edit_never_writes_world");
            require(
                ((java.util.Set<?>) get(session, "selectedIndices")).contains(get(session, "selected")),
                type + "_grade_keeps_batch_selection");
        }
        NBTTagCompound restore = (NBTTagCompound) initial.copy();
        restore.setIntArray("selectedIndices", new int[0]);
        world.getWorldInfo()
            .incrementTotalWorldTime(world.getTotalWorldTime() + 1);
        HologramService.handle(player, restore);
        settleCapture(world, session);
    }

    private HologramCapture collect(MTEEnhancedMultiBlockBase<?> machine, ItemStack signal) {
        HologramCapture capture = null;
        for (int i = 0; i < 128; i++) {
            capture = HologramCapture.collect(machine, signal, machine.getExtendedFacing(), capture);
            if (!capture.pending) break;
        }
        require(capture != null && !capture.pending && !capture.incomplete, "real_capture_complete");
        return capture;
    }

    private void settleCapture(WorldServer world, Object session) throws Exception {
        for (int i = 0; i < 128 && (Boolean) get(session, "capturePending"); i++) {
            world.getWorldInfo()
                .incrementTotalWorldTime(world.getTotalWorldTime() + 20);
            HologramService.tick();
        }
        require(!(Boolean) get(session, "capturePending"), "service_capture_settled");
    }

    private static Object session(EntityPlayerMP player) throws Exception {
        Field sessions = HologramService.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        return ((Map<?, ?>) sessions.get(null)).get(player.getUniqueID());
    }

    private static Object get(Object session, String name) throws Exception {
        Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }

    private static NBTTagCompound action(Object session, String op) throws Exception {
        NBTTagCompound action = new NBTTagCompound();
        action.setString("session", (String) get(session, "id"));
        action.setString("op", op);
        action.setLong("planRevision", (Long) get(session, "planRevision"));
        return action;
    }

    private void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        assertions++;
        if (!"fresh_fixture_air".equals(name)) System.out.println(PREFIX + "PASS " + name);
    }
}
