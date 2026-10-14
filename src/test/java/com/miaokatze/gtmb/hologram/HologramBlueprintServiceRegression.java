package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Production geometry edits against a world fixture that rejects every write. */
final class HologramBlueprintServiceRegression {

    static void run() {
        try {
            ItemStack stone = HologramArea.serverSample(Blocks.stone, 0, null);
            ItemStack chest = HologramArea.serverSample(Blocks.chest, 5, null);
            SimpleAssert.that(
                stone != null && stone.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone),
                "server stone sample uses registered block item without client pick hook");
            SimpleAssert.that(
                chest != null && chest.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.chest),
                "server chest sample keeps inventory identity");
            SimpleAssert.eq(0, chest.getItemDamage(), "chest inventory damage excludes world-facing metadata");
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            // Schema-2 ownership and resource plans use the real player profile, unlike the
            // older identity-only editor fixture. Unsafe skips the EntityPlayer constructor.
            Field profile = null;
            for (Field candidate : EntityPlayer.class.getDeclaredFields())
                if (candidate.getType() == GameProfile.class) {
                    profile = candidate;
                    break;
                }
            if (profile == null) throw new NoSuchFieldException("EntityPlayer GameProfile");
            profile.setAccessible(true);
            profile.set(
                player,
                new GameProfile(UUID.fromString("90a8679e-1f2d-44ef-b17e-5d22ba8d2c4b"), "BlueprintFixture"));
            for (Field entityField : net.minecraft.entity.Entity.class.getDeclaredFields())
                if (entityField.getType() == UUID.class) {
                    entityField.setAccessible(true);
                    entityField.set(player, UUID.fromString("90a8679e-1f2d-44ef-b17e-5d22ba8d2c4b"));
                }
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.isCreativeMode = true;
            player.capabilities.allowEdit = true;
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
            Constructor<?> ctor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            ctor.setAccessible(true);
            Object session = ctor.newInstance(player, 0, 64, 0, false, 2);
            set(session, "virtual", true);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.name = "fixture";
            preset.controllerType = "area";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false));
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 4, 0, 0, -1, false));
            set(session, "virtualPreset", preset);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
            settings.main = false;
            settings.containers = false;
            settings.me = false;
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            Method populate = method(type, "populateVirtual");
            populate.invoke(null, session);
            HologramCapture capture = (HologramCapture) get(session, "capture");
            SimpleAssert
                .that(!capture.incomplete, "schema2 item-only factory cells infer their block: " + capture.failure);
            capture.cells.get(0).prompt = "keep configuration";
            NBTTagCompound data = new NBTTagCompound();
            data.setString("setting", "saved-value");
            capture.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(data);
            Method move = HologramService.class.getDeclaredMethod("moveBlueprintCells", type, NBTTagCompound.class);
            move.setAccessible(true);
            NBTTagCompound action = new NBTTagCompound();
            action.setIntArray("indices", new int[] { 0 });
            action.setInteger("dx", 1);
            move.invoke(null, session, action);
            capture = (HologramCapture) get(session, "capture");
            SimpleAssert.eq(2, capture.cells.size(), "move keeps total cells");
            SimpleAssert.that(capture.positions.containsKey("2,64,0"), "move commits new coordinate");
            SimpleAssert.eq("keep configuration", capture.positions.get("2,64,0").prompt, "move preserves prompt");
            SimpleAssert.eq(
                "saved-value",
                HologramBlueprintNbt.decode(capture.positions.get("2,64,0").tileNbt)
                    .getString("setting"),
                "planning retains source NBT");
            action.setIntArray("indices", new int[] { capture.cells.indexOf(capture.positions.get("2,64,0")) });
            action.setInteger("dx", 2);
            Object original = get(session, "capture");
            try {
                move.invoke(null, session, action);
                throw new AssertionError("collision must reject whole move");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                SimpleAssert.that(expected.getCause() instanceof java.io.IOException, "collision is checked failure");
            }
            SimpleAssert.that(original == get(session, "capture"), "rejected geometry retains exact capture");
            Method scope = HologramService.class
                .getDeclaredMethod("inScope", type, HologramCapture.Cell.class, int.class);
            scope.setAccessible(true);
            HologramCapture.Cell cell = capture.cells.get(0);
            cell.excluded = true;
            SimpleAssert.that(!(Boolean) scope.invoke(null, session, cell, 0), "excluded cell cannot enter any scope");
            List<?> history = (List<?>) get(session, "undoEdits");
            Object undo = history.get(history.size() - 1);
            Method apply = undo.getClass()
                .getDeclaredMethod("apply", type);
            apply.setAccessible(true);
            apply.invoke(undo, session);
            capture = (HologramCapture) get(session, "capture");
            SimpleAssert.that(capture.positions.containsKey("1,64,0"), "geometry undo restores source coordinate");
            authorWorkflow(session);
            sourceSwitchLifecycle(session, player);
            gtPaymentIdentity(session, player, unsafe);
            ordinaryMaterialVariants(session.getClass(), player);
            sharedWall(session, player);
            completeStructureMoves(session, player);
            selectionLifecycle(player, session);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            cause.printStackTrace();
            throw new AssertionError(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static void ordinaryMaterialVariants(Class<?> type, EntityPlayerMP player)
        throws ReflectiveOperationException, java.io.IOException {
        Constructor<?> ctor = type
            .getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class, boolean.class, int.class);
        ctor.setAccessible(true);
        Object session = ctor.newInstance(player, 0, 64, 0, false, 2);
        set(session, "virtual", true);
        HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
        settings.main = false;
        settings.containers = false;
        settings.me = false;
        set(session, "materialContext", new HologramMaterials.Context(player, settings));
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        preset.name = "Material variants";
        preset.id = "material-variants-source";
        ItemStack white = new ItemStack(Blocks.wool, 1, 0);
        ItemStack black = new ItemStack(Blocks.wool, 1, 15);
        preset.cells.add(HologramPresets.cell(white, 0, 0, 0, -1, false));
        HologramPresets.Cell log = HologramPresets.cell(new ItemStack(Blocks.log, 1, 1), 1, 0, 0, -1, false);
        log.metadata = 9;
        preset.cells.add(log);
        set(session, "virtualPreset", preset);
        method(type, "populateVirtual").invoke(null, session);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        SimpleAssert
            .that(!capture.incomplete, "ordinary source variants pass production import admission: " + capture.failure);
        HologramCapture.Cell wool = capture.cells.get(0);
        List<ItemStack> targets = (List<ItemStack>) get(session, "planTargets");
        Method metadata = HologramService.class
            .getDeclaredMethod("targetPreviewMeta", type, HologramCapture.Cell.class, ItemStack.class);
        Method quote = HologramService.class
            .getDeclaredMethod("cachePlan", type, HologramCapture.Cell.class, int.class);
        Method operate = HologramService.class
            .getDeclaredMethod("operateChecked", type, HologramCapture.Cell.class, int.class);
        Method save = HologramService.class.getDeclaredMethod("blueprint", type, String.class);
        Method installed = HologramService.class.getDeclaredMethod(
            "validateOrdinaryPlacedVariant",
            net.minecraft.world.World.class,
            HologramCapture.Cell.class,
            ItemStack.class);
        Method matches = HologramService.class.getDeclaredMethod(
            "targetMatches",
            type,
            HologramCapture.Cell.class,
            int.class,
            net.minecraft.block.Block.class,
            int.class);
        for (Method method : new Method[] { metadata, quote, operate, save, installed, matches })
            method.setAccessible(true);
        net.minecraft.world.World originalWorld = player.worldObj;
        net.minecraft.nbt.NBTTagList inventoryBefore = new net.minecraft.nbt.NBTTagList();
        for (ItemStack stack : player.inventory.mainInventory)
            if (stack != null) inventoryBefore.appendTag(stack.writeToNBT(new NBTTagCompound()));
        try {
            // Bypass persistence admission deliberately to exercise the final Service boundary.
            wool.meta = 15;
            expectVariantFailure(metadata, session, wool, white);
            expectVariantFailure(quote, session, wool, 0);
            expectVariantFailure(save, session, "malformed white shell");
            SimpleAssert.that(
                !(Boolean) operate.invoke(null, session, wool, 0),
                "white payment plus imported black world metadata rejects before any world write or reservation");
            SimpleAssert.eq(15, wool.meta, "rejection does not silently normalize the imported material identity");
            wool.meta = 0;
            targets.set(0, black);
            SimpleAssert.eq(
                15,
                metadata.invoke(null, session, wool, black),
                "a valid white source with a selected black target uses black native placement metadata");
            SimpleAssert.that(
                quote.invoke(null, session, wool, 0) != null,
                "the production cache quote accepts the selected physical variant");
            HologramPresets.Preset exported = (HologramPresets.Preset) save.invoke(null, session, "black target");
            SimpleAssert.eq(15, exported.cells.get(0).damage, "export prices the selected black wool");
            SimpleAssert.eq(15, exported.cells.get(0).metadata, "export saves the selected black world metadata");
            SimpleAssert.eq(9, exported.cells.get(1).metadata, "export retains spruce log axis metadata");
            exported.id = "material-variant-regression";
            HologramPresets.validate(exported);
            HologramCapture.Cell placed = new HologramCapture.Cell(null, 0, 64, 0);
            placed.block = Blocks.wool;
            player.worldObj = HologramResourceWorld.create(Blocks.wool, 15);
            installed.invoke(null, player.worldObj, placed, black);
            expectVariantFailure(installed, player.worldObj, placed, white);
            SimpleAssert.that(
                (Boolean) matches.invoke(null, session, wool, 0, Blocks.wool, 15),
                "selected black placement satisfies the production target matcher");
            player.worldObj = HologramResourceWorld.create(Blocks.wool, 0);
            expectVariantFailure(installed, player.worldObj, placed, black);
            SimpleAssert.that(
                !(Boolean) matches.invoke(null, session, wool, 0, Blocks.wool, 0),
                "paying black cannot satisfy a white actual block");
            HologramCapture.Cell axis = capture.cells.get(1);
            SimpleAssert.eq(
                9,
                metadata.invoke(null, session, axis, new ItemStack(Blocks.log, 1, 1)),
                "the same spruce physical variant preserves its captured axis");
            SimpleAssert.eq(
                2,
                metadata.invoke(null, session, axis, new ItemStack(Blocks.log, 1, 2)),
                "a different log species receives the selected item's native metadata");
            Method select = HologramService.class
                .getDeclaredMethod("setBlueprintTarget", type, HologramCapture.Cell.class, ItemStack.class);
            select.setAccessible(true);
            select.invoke(null, session, axis, new ItemStack(Blocks.log, 1, 1));
            SimpleAssert.eq(9, axis.meta, "explicit selection of the same log species preserves its axis");
            select.invoke(null, session, wool, black);
            SimpleAssert.eq(15, wool.meta, "explicit black selection updates the captured placement state");
            SimpleAssert.eq(15, wool.blueprintDamage, "explicit black selection binds the new stored item identity");
            HologramPresets.validate((HologramPresets.Preset) save.invoke(null, session, "selected black target"));
            placed.block = Blocks.furnace;
            installed.invoke(
                null,
                HologramResourceWorld.create(Blocks.lit_furnace, 2),
                placed,
                new ItemStack(Blocks.furnace));
            placed.block = Blocks.redstone_torch;
            installed.invoke(
                null,
                HologramResourceWorld.create(Blocks.unlit_redstone_torch, 4),
                placed,
                new ItemStack(Blocks.redstone_torch));
            preset.cells.get(0).metadata = 15;
            player.worldObj = originalWorld;
            method(type, "populateVirtual").invoke(null, session);
            HologramCapture rejected = (HologramCapture) get(session, "capture");
            SimpleAssert.that(
                rejected.incomplete && "INVALID_PREFAB_VARIANT".equals(rejected.failure),
                "production import admission rejects white item identity with black saved metadata");
            preset.cells.get(0).metadata = 0;
            preset.cells.get(0).block = "minecraft:stone";
            method(type, "populateVirtual").invoke(null, session);
            rejected = (HologramCapture) get(session, "capture");
            SimpleAssert.that(
                rejected.incomplete && "INVALID_PREFAB_VARIANT".equals(rejected.failure),
                "an explicitly forged stored block cannot fall back to its wool item's block");
            net.minecraft.nbt.NBTTagList inventoryAfter = new net.minecraft.nbt.NBTTagList();
            for (ItemStack stack : player.inventory.mainInventory)
                if (stack != null) inventoryAfter.appendTag(stack.writeToNBT(new NBTTagCompound()));
            SimpleAssert.that(
                inventoryBefore.equals(inventoryAfter),
                "variant validation, export and failed placement preflight preserve the exact inventory");
        } finally {
            player.worldObj = originalWorld;
        }
    }

    private static void expectVariantFailure(Method method, Object... args) throws ReflectiveOperationException {
        try {
            method.invoke(null, args);
            throw new AssertionError("ordinary material identity mismatch must fail closed");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            SimpleAssert.that(
                expected.getCause() instanceof java.io.IOException,
                "ordinary material mismatch is a checked production rejection");
        }
    }

    private static void gtPaymentIdentity(Object session, EntityPlayerMP actor, sun.misc.Unsafe unsafe)
        throws ReflectiveOperationException, java.io.IOException {
        int id = 31007;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        HologramCapture capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell cell = capture.cells.get(0);
        String originalNbt = cell.tileNbt;
        @SuppressWarnings("unchecked")
        List<ItemStack> targets = (List<ItemStack>) get(session, "planTargets");
        List<ItemStack> previousTargets = new java.util.ArrayList<>(targets);
        HologramPresets.Preset preset = (HologramPresets.Preset) get(session, "virtualPreset");
        boolean originalRetain = preset.retainNbt;
        Object originalCapture = get(session, "capture");
        net.minecraft.nbt.NBTTagList before = new net.minecraft.nbt.NBTTagList();
        for (ItemStack stack : actor.inventory.mainInventory)
            if (stack != null) before.appendTag(stack.writeToNBT(new NBTTagCompound()));
        Method quote = HologramService.class
            .getDeclaredMethod("cachePlan", session.getClass(), HologramCapture.Cell.class, int.class);
        Method identity = HologramService.class
            .getDeclaredMethod("validateGtPayloadIdentity", ItemStack.class, NBTTagCompound.class);
        Method installed = HologramService.class.getDeclaredMethod(
            "validateGtPlacedIdentity",
            ItemStack.class,
            net.minecraft.block.Block.class,
            net.minecraft.tileentity.TileEntity.class);
        quote.setAccessible(true);
        identity.setAccessible(true);
        installed.setAccessible(true);
        try {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = (gregtech.api.interfaces.metatileentity.IMetaTileEntity) unsafe
                .allocateInstance(gregtech.api.metatileentity.implementations.MTEHatchInput.class);
            ItemStack target = registeredGtMaterial(id);
            net.minecraft.block.Block paidBlock = net.minecraft.block.Block.getBlockFromItem(target.getItem());
            SimpleAssert.that(
                paidBlock != null && paidBlock != Blocks.air,
                "the paid GT fixture material resolves through the real Forge block/item registry");
            while (targets.size() <= 0) targets.add(null);
            targets.set(0, target);
            preset.retainNbt = true;
            NBTTagCompound hostile = new NBTTagCompound();
            hostile.setString("id", "BaseMetaTileEntity");
            hostile.setShort("mID", (short) (id + 1));
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(hostile);
            expectGtIdentityFailure(quote, session, cell, 0);
            SimpleAssert.that(
                originalCapture == get(session, "capture") && hostile.equals(HologramBlueprintNbt.decode(cell.tileNbt)),
                "a mismatched imported GT identity is rejected before quoting without rewriting the source");
            hostile.setLong("mID", (1L << 32) + id);
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(hostile);
            expectGtIdentityFailure(quote, session, cell, 0);
            hostile.setDouble("mID", id);
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(hostile);
            expectGtIdentityFailure(quote, session, cell, 0);
            hostile.setShort("mID", (short) id);
            hostile.setString("id", "Chest");
            expectGtIdentityFailure(identity, target, hostile);
            hostile.removeTag("id");
            identity.invoke(null, target, hostile);
            hostile.setString("id", "BaseMetaTileEntity");
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(hostile);
            SimpleAssert.that(
                quote.invoke(null, session, cell, 0) != null,
                "the production quote accepts the paid GT identity and the native configuration projection");
            net.minecraft.tileentity.TileEntity actual = (net.minecraft.tileentity.TileEntity) unsafe
                .allocateInstance(gregtech.api.metatileentity.BaseMetaTileEntity.class);
            Field metaId = null;
            for (Class<?> owner = actual.getClass(); owner != null && metaId == null; owner = owner.getSuperclass())
                try {
                    metaId = owner.getDeclaredField("mID");
                } catch (NoSuchFieldException ignored) {}
            if (metaId == null) throw new NoSuchFieldException("GT native mID");
            metaId.setAccessible(true);
            setNativeMetaId(metaId, actual, id + 1);
            SimpleAssert.eq(
                id + 1,
                ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) actual).getMetaTileID(),
                "the real native GT getter observes the fixture's mismatched mID");
            expectGtIdentityFailure(installed, target, paidBlock, actual);
            setNativeMetaId(metaId, actual, id);
            SimpleAssert.eq(
                id,
                ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) actual).getMetaTileID(),
                "the real native GT getter observes the initialized paid mID");
            installed.invoke(null, target, paidBlock, actual);
            expectGtIdentityFailure(installed, target, Blocks.chest, actual);
            net.minecraft.nbt.NBTTagList after = new net.minecraft.nbt.NBTTagList();
            for (ItemStack stack : actor.inventory.mainInventory)
                if (stack != null) after.appendTag(stack.writeToNBT(new NBTTagCompound()));
            SimpleAssert
                .that(before.equals(after), "GT identity failures and quote validation never consume inventory");
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
            cell.tileNbt = originalNbt;
            preset.retainNbt = originalRetain;
            targets.clear();
            targets.addAll(previousTargets);
        }
    }

    private static ItemStack registeredGtMaterial(int id) {
        String name = "gtmb:blueprint_service_identity_fixture";
        net.minecraft.item.Item material = (net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry
            .getObject(name);
        if (material == null) {
            int free = 3998;
            while (free > 0 && (net.minecraft.item.Item.getItemById(free) != null
                || net.minecraft.block.Block.getBlockById(free) != null
                    && net.minecraft.block.Block.getBlockById(free) != Blocks.air))
                free--;
            if (free == 0) throw new AssertionError("No isolated GT material registry slot");
            net.minecraft.block.Block block = new net.minecraft.block.Block(
                net.minecraft.block.material.Material.iron) {};
            net.minecraft.block.Block.blockRegistry.addObject(free, name, block);
            material = new gregtech.common.blocks.ItemMachines(block);
            net.minecraft.item.Item.itemRegistry.addObject(free, name, material);
        }
        return new ItemStack(material, 1, id);
    }

    private static void setNativeMetaId(Field field, Object tile, int id) throws IllegalAccessException {
        if (field.getType() == short.class) field.setShort(tile, (short) id);
        else field.setInt(tile, id);
    }

    private static void expectGtIdentityFailure(Method method, Object... args) throws ReflectiveOperationException {
        try {
            method.invoke(null, args);
            throw new AssertionError("GT identity mismatch must reject before payment commit");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            SimpleAssert.that(
                expected.getCause() instanceof java.io.IOException,
                "GT identity mismatch is a checked production rejection");
        }
    }

    @SuppressWarnings("unchecked")
    private static void sourceSwitchLifecycle(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException {
        Class<?> type = session.getClass();
        Method propose = HologramService.class.getDeclaredMethod(
            "requestSourceSwitch",
            type,
            NBTTagCompound.class,
            net.minecraft.tileentity.TileEntity.class,
            Object.class,
            HologramArea.class);
        Method confirmed = HologramService.class.getDeclaredMethod("sourceSwitchConfirmed", type, NBTTagCompound.class);
        Method areaCurrent = method(type, "sourceSwitchAreaCurrent");
        Method cancel = HologramService.class.getDeclaredMethod("cancelSourceSwitch", type, NBTTagCompound.class);
        Method confirm = HologramService.class.getDeclaredMethod("confirmSourceSwitch", type, NBTTagCompound.class);
        propose.setAccessible(true);
        confirmed.setAccessible(true);
        cancel.setAccessible(true);
        confirm.setAccessible(true);
        Field areaField = HologramService.class.getDeclaredField("AREAS");
        areaField.setAccessible(true);
        Map<UUID, HologramArea> areas = (Map<UUID, HologramArea>) areaField.get(null);
        Field networkChannel = HologramNetwork.class.getDeclaredField("channel");
        networkChannel.setAccessible(true);
        Object originalNetworkChannel = networkChannel.get(null);
        HologramArea originalArea = areas.get(player.getUniqueID());
        boolean originalDirty = (Boolean) get(session, "blueprintDirty");
        String originalSource = (String) get(session, "blueprintSource");
        Object originalCapture = get(session, "capture"), originalPreset = get(session, "virtualPreset");
        try {
            // Build the real preview DTO while the ordinary transport's null channel prevents socket writes.
            networkChannel.set(null, null);
            set(session, "blueprintDirty", true);
            HologramArea area = new HologramArea();
            area.point(1, 1, -1, 0);
            area.point(2, 2, -1, 0);
            areas.put(player.getUniqueID(), area);
            NBTTagCompound action = new NBTTagCompound();
            action.setString("op", "importArea");
            action.setString("presetId", "area");
            propose.invoke(null, session, action, null, null, area);
            NBTTagCompound proposal = (NBTTagCompound) get(session, "pendingSourceAction");
            NBTTagCompound request = (NBTTagCompound) proposal.copy();
            SimpleAssert.that(
                !(Boolean) confirmed.invoke(null, session, request),
                "a source proposal cannot itself discard the current blueprint");
            request.setBoolean("confirmDiscard", true);
            SimpleAssert.that(
                (Boolean) confirmed.invoke(null, session, request),
                "only explicit confirmation authorizes the bound source proposal");
            request.setString("sourceSwitchNonce", "another-proposal");
            SimpleAssert.that(
                !(Boolean) confirmed.invoke(null, session, request),
                "another source nonce cannot authorize discard");
            cancel.invoke(null, session, request);
            SimpleAssert.that(
                proposal == get(session, "pendingSourceAction"),
                "a stale cancel cannot dismiss the active source proposal");
            request = (NBTTagCompound) proposal.copy();
            cancel.invoke(null, session, request);
            SimpleAssert.that(
                !(Boolean) get(session, "discardConfirmationRequired")
                    && ((NBTTagCompound) get(session, "pendingSourceAction")).hasNoTags(),
                "cancel clears only the pending source confirmation");
            SimpleAssert.that(
                (Boolean) get(session, "blueprintDirty") && originalCapture == get(session, "capture")
                    && originalPreset == get(session, "virtualPreset"),
                "cancel retains the exact edited capture and blueprint");
            propose.invoke(null, session, action, null, null, area);
            request = (NBTTagCompound) ((NBTTagCompound) get(session, "pendingSourceAction")).copy();
            request.setBoolean("confirmDiscard", true);
            request.setLong("planRevision", request.getLong("planRevision") - 1);
            SimpleAssert.that(
                !(Boolean) confirmed.invoke(null, session, request),
                "a source confirmation from another plan cannot discard current edits");
            SimpleAssert
                .that((Boolean) areaCurrent.invoke(null, session), "the proposed area is bound to its exact mask");
            area.point(2, 3, -1, 0);
            SimpleAssert.that(
                !(Boolean) areaCurrent.invoke(null, session),
                "changing either area corner invalidates its pending source confirmation");
            area.point(2, 2, -1, 0);
            request = (NBTTagCompound) ((NBTTagCompound) get(session, "pendingSourceAction")).copy();
            request.setBoolean("confirmDiscard", true);
            confirm.invoke(null, session, request);
            SimpleAssert.that(
                !area.importing && (Boolean) get(session, "blueprintDirty")
                    && originalSource.equals(get(session, "blueprintSource")),
                "failed area validation retains the old dirty source instead of falsely marking it discarded");
            SimpleAssert.that(
                originalCapture == get(session, "capture") && originalPreset == get(session, "virtualPreset"),
                "failed source import cannot replace the current blueprint");
            area = new HologramArea();
            area.point(1, 1, 64, 0);
            area.point(2, 2, 64, 0);
            areas.put(player.getUniqueID(), area);
            propose.invoke(null, session, action, null, null, area);
            request = (NBTTagCompound) ((NBTTagCompound) get(session, "pendingSourceAction")).copy();
            request.setBoolean("confirmDiscard", true);
            confirm.invoke(null, session, request);
            SimpleAssert.that(
                area.importing && originalCapture == get(session, "capture"),
                "explicit discard starts bounded import while keeping the old capture until completion");
            method(type, "cancelAreaImport").invoke(null, session);
            SimpleAssert.that(
                !area.importing && (Boolean) get(session, "blueprintDirty")
                    && originalSource.equals(get(session, "blueprintSource"))
                    && originalCapture == get(session, "capture")
                    && originalPreset == get(session, "virtualPreset"),
                "cancelling unfinished source import restores the old blueprint's unsaved status");
        } finally {
            networkChannel.set(null, originalNetworkChannel);
            set(session, "capturePending", false);
            set(session, "blueprintDirty", originalDirty);
            set(session, "blueprintSource", originalSource);
            if (originalArea == null) areas.remove(player.getUniqueID());
            else areas.put(player.getUniqueID(), originalArea);
            method(type, "clearSourceSwitch").invoke(null, session);
        }
    }

    private static void authorWorkflow(Object session) throws ReflectiveOperationException, java.io.IOException {
        Class<?> type = session.getClass();
        Method gate = HologramService.class
            .getDeclaredMethod("requireBlueprintProcessing", type, HologramPresets.Preset.class);
        gate.setAccessible(true);
        HologramPresets.Preset raw = (HologramPresets.Preset) get(session, "virtualPreset");
        EntityPlayerMP actor = (EntityPlayerMP) get(session, "player");
        HologramCapture captured = (HologramCapture) get(session, "capture");
        NBTTagCompound identity = HologramBlueprintNbt.decode(captured.cells.get(0).tileNbt);
        if (identity == null) identity = new NBTTagCompound();
        identity.setString(
            "ownerUUID",
            actor.getUniqueID()
                .toString());
        captured.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(identity);
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 1);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset forgedReceipt = HologramBlueprintProcessing.process(raw, policy, 0, 0, 64, 0);
        try {
            gate.invoke(null, session, forgedReceipt);
            throw new AssertionError("uploaded complete receipt must not authorize an author save");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            SimpleAssert.that(
                expected.getCause() instanceof java.io.IOException,
                "save gate rejects an unreviewed server session");
        }
        Method process = HologramService.class
            .getDeclaredMethod("processingAction", type, NBTTagCompound.class, String.class);
        process.setAccessible(true);
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", (Long) get(session, "planRevision"));
        request.setTag("policy", policy.write());
        process.invoke(null, session, request, "processBlueprint");
        SimpleAssert.that(
            (Boolean) get(session, "processingReviewed"),
            "production server process authorizes save after all six stages: " + get(session, "status"));
        SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "processing output remains dirty until saved");
        gate.invoke(null, session, get(session, "virtualPreset"));
        Method edited = HologramService.class.getDeclaredMethod("blueprintEdited", type, String.class);
        edited.setAccessible(true);
        edited.invoke(null, session, "prompt");
        HologramPresets.Preset retainedRaw = (HologramPresets.Preset) get(session, "processingSource");
        SimpleAssert.eq(
            actor.getUniqueID()
                .toString(),
            HologramBlueprintNbt.decode(retainedRaw.cells.get(0).tileNbt)
                .getString("ownerUUID"),
            "prompt editing preserves the session's immutable raw identity for a later explicit review");
        SimpleAssert
            .eq(0, HologramDeidentification.level(retainedRaw), "raw identity floor survives a prompt-only edit");
        policy = HologramBlueprintProcessing.defaults("self", 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        request.setLong("planRevision", (Long) get(session, "planRevision"));
        request.setTag("policy", policy.write());
        process.invoke(null, session, request, "processBlueprint");
        SimpleAssert.that(
            (Boolean) get(session, "processingReviewed"),
            "new explicit review may project the retained raw source at level zero");
        SimpleAssert.eq(
            0,
            HologramDeidentification.level((HologramPresets.Preset) get(session, "virtualPreset")),
            "explicitly reviewed raw identity is restored at the actual zero level");
        edited.invoke(null, session, "nbt");
        SimpleAssert.that(!(Boolean) get(session, "processingReviewed"), "NBT edits revoke server save authority");
        HologramBlueprintProcessing.Policy invalidated = (HologramBlueprintProcessing.Policy) get(
            session,
            "processingPolicy");
        SimpleAssert.eq(
            4,
            invalidated.confirmedStages.size(),
            "NBT dependency retains reviewed identity and coordinate stages");
        Method discard = HologramService.class
            .getDeclaredMethod("allowBlueprintDiscard", type, NBTTagCompound.class, String.class, String.class);
        discard.setAccessible(true);
        NBTTagCompound switchSource = new NBTTagCompound();
        SimpleAssert.that(
            !(Boolean) discard.invoke(null, session, switchSource, "editPreset", "other"),
            "dirty source switch requires explicit discard");
        SimpleAssert.eq("other", get(session, "discardTargetId"), "discard review identifies the intended target");
        switchSource.setBoolean("confirmDiscard", true);
        SimpleAssert.that(
            (Boolean) discard.invoke(null, session, switchSource, "editPreset", "other"),
            "explicit discard permits only the requested source switch");
        Method receipt = HologramService.class
            .getDeclaredMethod("acknowledgeBlueprintSave", type, NBTTagCompound.class);
        receipt.setAccessible(true);
        long revision = (Long) get(session, "planRevision");
        ItemStack originalTool = actor.getHeldItem();
        Object originalToolItem = get(session, "tool"), originalBoundTool = get(session, "boundTool");
        Object originalUiActive = get(session, "libraryUiActive");
        net.minecraft.network.NetHandlerPlayServer originalConnection = actor.playerNetServerHandler;
        ItemStack projector = new ItemStack(new com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector());
        projector.setTagCompound(
            (NBTTagCompound) originalTool.getTagCompound()
                .copy());
        actor.inventory.mainInventory[actor.inventory.currentItem] = projector;
        set(session, "tool", projector.getItem());
        set(session, "boundTool", projector);
        set(session, "libraryUiActive", true);
        net.minecraft.network.NetworkManager connection = new net.minecraft.network.NetworkManager(false);
        io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel(
            new io.netty.channel.ChannelInboundHandlerAdapter());
        for (Field connectionField : net.minecraft.network.NetworkManager.class.getDeclaredFields())
            if (connectionField.getType() == io.netty.channel.Channel.class) {
                connectionField.setAccessible(true);
                connectionField.set(connection, channel);
            }
        new net.minecraft.network.NetHandlerPlayServer(null, connection, actor);
        Field sessionsField = HologramService.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> registered = (Map<UUID, Object>) sessionsField.get(null);
        Object originalSession = registered.put(actor.getUniqueID(), session);
        try {
            set(session, "blueprintSaveReceipt", "bounded-save-receipt");
            set(session, "blueprintSaveLocalId", "local_saved_fixture");
            set(session, "blueprintSaveRevision", revision);
            HologramPresets.Preset exported = HologramPresets
                .copy((HologramPresets.Preset) get(session, "virtualPreset"));
            exported.id = "local_saved_fixture";
            HologramBlueprintVersions.stamp(exported);
            byte[] exactJson = HologramServerLibrary.encodeShare(exported);
            byte[] boundPng = HologramBlueprintArtifact
                .bindPreview(exactJson, com.miaokatze.gtmb.testutil.BlueprintArtifactTestData.previewPng());
            HologramBlueprintArtifact.Artifact proof = HologramBlueprintArtifact
                .decode(HologramBlueprintArtifact.encode(exactJson, boundPng));
            NBTTagCompound acknowledgement = new NBTTagCompound();
            acknowledgement.setString("session", (String) get(session, "id"));
            acknowledgement.setLong("planRevision", revision);
            acknowledgement.setString("localId", "local_saved_fixture");
            acknowledgement.setString("jsonSha256", proof.jsonSha256);
            acknowledgement.setString("previewSha256", proof.pngSha256);
            acknowledgement.setString("saveReceipt", "another-receipt");
            SimpleAssert.that(
                !(Boolean) receipt.invoke(null, session, acknowledgement),
                "another download cannot clear a dirty blueprint");
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "an unacknowledged save remains dirty");
            acknowledgement.setString("saveReceipt", "bounded-save-receipt");
            acknowledgement.setLong("planRevision", revision - 1);
            SimpleAssert.that(
                !(Boolean) receipt.invoke(null, session, acknowledgement),
                "a stale disk receipt cannot acknowledge a changed plan");
            acknowledgement.setLong("planRevision", revision);
            SimpleAssert.that(
                HologramService.libraryDraftPrepared(actor, acknowledgement, proof.jsonSha256),
                "actual server JSON digest is armed under the matching source save nonce");
            SimpleAssert.that(
                !(Boolean) receipt.invoke(null, session, acknowledgement),
                "JSON-only disk receipt cannot succeed before the matching PNG is verified");
            SimpleAssert.that(
                !HologramService.libraryPreviewVerified(actor, acknowledgement, proof.pngSha256, proof.pngSha256),
                "PNG for another JSON cannot arm a save receipt");
            SimpleAssert.that(
                HologramService.libraryPreviewVerified(actor, acknowledgement, proof.jsonSha256, proof.pngSha256),
                "real source-bound PNG proof arms its own digest");
            acknowledgement.setString("jsonSha256", proof.pngSha256);
            SimpleAssert.that(
                !(Boolean) receipt.invoke(null, session, acknowledgement),
                "rewritten JSON digest cannot acknowledge the saved artifact");
            acknowledgement.setString("jsonSha256", proof.jsonSha256);
            acknowledgement.setString("previewSha256", proof.jsonSha256);
            SimpleAssert.that(
                !(Boolean) receipt.invoke(null, session, acknowledgement),
                "another PNG digest cannot acknowledge the saved artifact");
            acknowledgement.setString("previewSha256", proof.pngSha256);
            SimpleAssert.that(
                (Boolean) receipt.invoke(null, session, acknowledgement),
                "matching owner session revision target and receipt acknowledges actual saved output");
            SimpleAssert
                .that(!(Boolean) get(session, "blueprintDirty"), "only the matching save receipt clears dirty state");
            SimpleAssert.that(!(Boolean) receipt.invoke(null, session, acknowledgement), "save receipt is single-use");
        } finally {
            if (originalSession == null) registered.remove(actor.getUniqueID());
            else registered.put(actor.getUniqueID(), originalSession);
            actor.inventory.mainInventory[actor.inventory.currentItem] = originalTool;
            set(session, "tool", originalToolItem);
            set(session, "boundTool", originalBoundTool);
            set(session, "libraryUiActive", originalUiActive);
            actor.playerNetServerHandler = originalConnection;
            channel.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void sharedWall(Object session, EntityPlayerMP player) throws ReflectiveOperationException {
        Class<?> type = session.getClass();
        HologramPresets.Preset preset = (HologramPresets.Preset) get(session, "virtualPreset");
        HologramPresets.Structure a = new HologramPresets.Structure();
        a.id = "A";
        a.name = "A";
        HologramPresets.Structure b = new HologramPresets.Structure();
        b.id = "B";
        b.name = "B";
        a.enabled = false;
        b.enabled = true;
        preset.structures.add(a);
        preset.structures.add(b);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell shared = capture.positions.get("1,64,0");
        shared.structureId = "A";
        shared.structureIds = new java.util.ArrayList<>(java.util.Arrays.asList("A", "B"));
        shared.tileNbt = "";
        shared.itemNbt = "";
        capture.positions.get("4,64,0").structureId = "B";
        HologramCapture.Cell onlyA = addFixtureCell(session, 7, "A", false);
        HologramArea area = new HologramArea();
        area.addMember("1,64,0", "A");
        area.addMember("1,64,0", "B");
        area.addMember("1,64,0", "B");
        SimpleAssert.eq(
            2,
            area.memberships(1, 64, 0)
                .size(),
            "region captures both owners without duplicate membership");
        Method scope = HologramService.class.getDeclaredMethod("inScope", type, HologramCapture.Cell.class, int.class);
        scope.setAccessible(true);
        set(session, "scope", 0);
        set(session, "blueprintActiveStructure", "");
        SimpleAssert
            .that((Boolean) scope.invoke(null, session, shared, 0), "shared wall builds when only B is enabled");
        b.enabled = false;
        SimpleAssert
            .that(!(Boolean) scope.invoke(null, session, shared, 0), "shared wall is outside scope when both disabled");
        b.enabled = true;
        Method select = HologramService.class.getDeclaredMethod("selectAreaStructure", type, String.class);
        select.setAccessible(true);
        select.invoke(null, session, "B");
        java.util.Set<Integer> selected = (java.util.Set<Integer>) get(session, "selectedIndices");
        SimpleAssert.that(selected.contains(capture.cells.indexOf(shared)), "selecting B includes the shared wall");
        set(session, "scope", 0);
        SimpleAssert.that(
            !(Boolean) scope.invoke(null, session, onlyA, capture.cells.indexOf(onlyA)),
            "active B excludes A even with global scope");
        select.invoke(null, session, "A");
        SimpleAssert.that(
            !(Boolean) scope.invoke(null, session, shared, 0),
            "active disabled A excludes shared wall despite enabled B");
        select.invoke(null, session, "B");
        shared.excluded = true;
        SimpleAssert
            .that(!(Boolean) scope.invoke(null, session, shared, 0), "explicit exclusion dominates shared ownership");
        shared.excluded = false;
        selected.clear();
        selected.add(capture.cells.indexOf(shared));
        set(session, "scope", 2);
        set(session, "virtualPlacement", true);
        set(session, "virtualLocked", true);
        player.posY = 64;
        method(type, "refreshPlan").invoke(null, session);
        Method build = HologramService.class.getDeclaredMethod("directBuild", type, boolean.class);
        build.setAccessible(true);
        build.invoke(null, session, false);
        List<Integer> work = (List<Integer>) get(session, "work");
        int sharedIndex = capture.cells.indexOf(shared);
        int otherIndex = capture.cells.indexOf(capture.positions.get("4,64,0"));
        SimpleAssert.eq(2, work.size(), "whole blueprint includes the shared wall and the other enabled physical cell");
        SimpleAssert.eq(
            1,
            java.util.Collections.frequency(work, sharedIndex),
            "two owners schedule only one work index and one material transaction for their shared wall");
        SimpleAssert.eq(
            1,
            java.util.Collections.frequency(work, otherIndex),
            "inspection selection cannot exclude the other enabled blueprint position");
        SimpleAssert.eq(
            0,
            java.util.Collections.frequency(work, capture.cells.indexOf(onlyA)),
            "whole placement preserves the disabled owner-only cell exclusion");
        assertWholeSharedBudget(session, player);
        SimpleAssert.that(
            player.worldObj.isAirBlock(shared.x, shared.y, shared.z) && player.worldObj.isAirBlock(4, 64, 0),
            "scheduling two physical positions does not write the read-only world");
        set(session, "job", 0);
        Method save = HologramService.class.getDeclaredMethod("blueprint", type, String.class);
        save.setAccessible(true);
        HologramPresets.Preset saved = (HologramPresets.Preset) save.invoke(null, session, "shared");
        int savedWalls = 0;
        for (HologramPresets.Cell cell : saved.cells) if (cell.x == 1 && cell.y == 0 && cell.z == 0) {
            savedWalls++;
            SimpleAssert.that(cell.belongsTo("A") && cell.belongsTo("B"), "save retains both memberships");
        }
        SimpleAssert.eq(1, savedWalls, "saved blueprint has one physical wall item");
        Method rebase = HologramService.class
            .getDeclaredMethod("moveVirtual", type, int.class, int.class, int.class, int.class);
        rebase.setAccessible(true);
        rebase.invoke(null, session, 3, 64, 0, 2);
        capture = (HologramCapture) get(session, "capture");
        shared = capture.positions.get("4,64,0");
        SimpleAssert.that(shared.belongsTo("A") && shared.belongsTo("B"), "rebase/clone preserves both owners");
        set(session, "blueprintActiveStructure", "");
        HologramCapture.Cell controllerA = addFixtureCell(session, 3, "A", true);
        Method move = HologramService.class.getDeclaredMethod("moveBlueprintCells", type, NBTTagCompound.class);
        move.setAccessible(true);
        NBTTagCompound action = new NBTTagCompound();
        action.setBoolean("copy", true);
        action.setInteger("dx", 12);
        action.setIntArray(
            "indices",
            new int[] { capture.cells.indexOf(controllerA), capture.cells.indexOf(shared),
                capture.cells.indexOf(capture.positions.get("10,64,0")) });
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell copiedWall = capture.positions.get("16,64,0");
        SimpleAssert.eq(
            1,
            copiedWall.memberships()
                .size(),
            "copy A alone does not attach its new wall to original B");
        SimpleAssert.that(
            !copiedWall.belongsTo("A") && !copiedWall.belongsTo("B"),
            "copy A receives a fresh structure identity");
        shared = capture.positions.get("4,64,0");
        SimpleAssert.that(shared.belongsTo("A") && shared.belongsTo("B"), "copy does not mutate source memberships");
        HologramCapture.Cell controllerB = addFixtureCell(session, 9, "B", true);
        b.x = 6;
        action.setInteger("dx", 24);
        action.setIntArray(
            "indices",
            new int[] { capture.cells.indexOf(shared), capture.cells.indexOf(capture.positions.get("3,64,0")),
                capture.cells.indexOf(controllerB), capture.cells.indexOf(capture.positions.get("10,64,0")),
                capture.cells.indexOf(capture.positions.get("7,64,0")) });
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        copiedWall = capture.positions.get("28,64,0");
        SimpleAssert.eq(
            2,
            copiedWall.memberships()
                .size(),
            "copy both structures retains a shared new wall");
        SimpleAssert
            .that(!copiedWall.belongsTo("A") && !copiedWall.belongsTo("B"), "both copied memberships are rekeyed");
        Method cut = HologramService.class
            .getDeclaredMethod("blueprintClipboard", type, NBTTagCompound.class, String.class);
        cut.setAccessible(true);
        action = new NBTTagCompound();
        action.setIntArray("indices", new int[] { capture.cells.indexOf(capture.positions.get("4,64,0")) });
        cut.invoke(null, session, action, "editCut");
        List<HologramCapture.Cell> clipboard = (List<HologramCapture.Cell>) get(session, "blueprintClipboard");
        SimpleAssert.eq(1, clipboard.size(), "shared wall is copied once into clipboard");
        SimpleAssert.that(
            clipboard.get(0)
                .belongsTo("A")
                && clipboard.get(0)
                    .belongsTo("B"),
            "cut preserves shared membership in clipboard");
    }

    private static void assertWholeSharedBudget(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException {
        HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
        boolean originalCreative = player.capabilities.isCreativeMode, originalMain = settings.main;
        ItemStack originalStock = player.inventory.mainInventory[1];
        Object originalContext = get(session, "materialContext");
        try {
            player.capabilities.isCreativeMode = false;
            settings.main = true;
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 2);
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            method(session.getClass(), "refreshResources").invoke(null, session);
            HologramBlueprintResources.Plan total = (HologramBlueprintResources.Plan) get(
                session,
                "blueprintTotalResources");
            int requiredStone = 0;
            for (ItemStack cost : total.items)
                if (cost.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone))
                    requiredStone += cost.stackSize;
            SimpleAssert
                .eq(2, requiredStone, "whole plan budgets shared stone once plus the other physical stone once");
            SimpleAssert.eq(0, get(session, "planMissing"), "two actual stone materials satisfy both physical cells");
            SimpleAssert
                .eq(2, player.inventory.mainInventory[1].stackSize, "shared-wall material review never consumes stock");
        } finally {
            player.capabilities.isCreativeMode = originalCreative;
            settings.main = originalMain;
            player.inventory.mainInventory[1] = originalStock;
            set(session, "materialContext", originalContext);
        }
    }

    @SuppressWarnings("unchecked")
    private static void completeStructureMoves(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException, java.io.IOException {
        Class<?> type = session.getClass();
        set(session, "x", 0);
        set(session, "y", 64);
        set(session, "z", 0);
        set(session, "blueprintActiveStructure", "");
        set(session, "scope", 0);
        set(session, "job", 0);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.name = "Complete structure fixture";
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = "whole-A";
        structure.name = "A";
        preset.structures.add(structure);
        for (int x = 1; x <= 3; x++) {
            HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), x, 0, 0, -1, false);
            if (x < 3) cell.structureId = structure.id;
            preset.cells.add(cell);
        }
        set(session, "virtualPreset", preset);
        method(type, "populateVirtual").invoke(null, session);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        capture.cells.get(0).role = "templateController";
        capture.cells.get(0).prompt = "copy costed inventory and settings";
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("setting", "retained");
        net.minecraft.nbt.NBTTagList inventory = new net.minecraft.nbt.NBTTagList();
        inventory.appendTag(new ItemStack(Blocks.dirt, 3).writeToNBT(new NBTTagCompound()));
        payload.setTag("inventory", inventory);
        capture.cells.get(1).tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        Method move = HologramService.class.getDeclaredMethod("moveBlueprintCells", type, NBTTagCompound.class);
        move.setAccessible(true);
        NBTTagCompound action = new NBTTagCompound();
        action.setIntArray("indices", new int[] { 1 });
        action.setInteger("dx", 4);
        try {
            move.invoke(null, session, action);
            throw new AssertionError("structure interior cannot move independently");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert.that(rejected.getCause() instanceof java.io.IOException, "partial structure move rejected");
        }
        SimpleAssert.that(capture == get(session, "capture"), "partial rejection is atomic");
        action.setIntArray("indices", new int[] { 0, 1 });
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.that(
            capture.positions.containsKey("5,64,0") && capture.positions.containsKey("6,64,0"),
            "whole structure moves together");
        SimpleAssert.that(!capture.positions.containsKey("1,64,0"), "move removes its former exclusive source");
        action.setBoolean("copy", true);
        for (int repeat = 0; repeat < 3; repeat++) {
            java.util.Set<Integer> selected = (java.util.Set<Integer>) get(session, "selectedIndices");
            action.setIntArray(
                "indices",
                selected.stream()
                    .mapToInt(Integer::intValue)
                    .toArray());
            move.invoke(null, session, action);
        }
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.eq(9, capture.cells.size(), "three copies add six planned cells");
        SimpleAssert.that(capture.positions.containsKey("5,64,0"), "copy retains original source");
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (HologramPresets.Structure current : preset.structures) identities.add(current.id);
        SimpleAssert.eq(4, identities.size(), "every duplicated structure has an independent UUID");
        SimpleAssert.eq(4, preset.structures.size(), "copy does not duplicate structure descriptors twice");
        SimpleAssert.eq(
            "copy costed inventory and settings",
            capture.positions.get("17,64,0").prompt,
            "copy preserves prompts");
        HologramBlueprintResources.Plan resources = (HologramBlueprintResources.Plan) get(
            session,
            "blueprintResources");
        int inventoryCost = 0;
        for (ItemStack stack : resources.items)
            if (stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt))
                inventoryCost += stack.stackSize;
        SimpleAssert.eq(12, inventoryCost, "all four retained inventories require real payment");
        int loose = capture.cells.indexOf(capture.positions.get("3,64,0"));
        action.setBoolean("copy", false);
        action.setIntArray("indices", new int[] { loose });
        action.setInteger("dx", 1);
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.that(capture.positions.containsKey("4,64,0"), "independent unstructured block can move");
        action.setIntArray("indices", new int[] { -1 });
        try {
            move.invoke(null, session, action);
            throw new AssertionError("invalid selection must reject");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert.that(rejected.getCause() instanceof java.io.IOException, "invalid indices fail closed");
        }
        Method add = HologramService.class.getDeclaredMethod("addPosition", type, NBTTagCompound.class);
        add.setAccessible(true);
        NBTTagCompound air = new NBTTagCompound();
        air.setInteger("dx", 19);
        air.setInteger("dy", 0);
        air.setInteger("dz", 0);
        air.setBoolean("airOnly", true);
        add.invoke(null, session, air);
        capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell virtualAir = capture.positions.get("19,64,0");
        SimpleAssert
            .that(virtualAir != null && virtualAir.block == Blocks.air, "air voxel receives canonical position");
        int count = capture.cells.size();
        add.invoke(null, session, air);
        SimpleAssert.eq(count, capture.cells.size(), "repeated air selection cannot duplicate a position");
        NBTTagCompound remoteAir = (NBTTagCompound) air.copy();
        remoteAir.setInteger("dx", 1000);
        add.invoke(null, session, remoteAir);
        SimpleAssert.eq(count, capture.cells.size(), "air pick outside blueprint bounds is rejected on server");
        Method save = HologramService.class.getDeclaredMethod("blueprint", type, String.class);
        save.setAccessible(true);
        HologramPresets.Preset saved = (HologramPresets.Preset) save.invoke(null, session, "air-preserving");
        boolean savedAir = false;
        for (HologramPresets.Cell cell : saved.cells) if (cell.x == 19) {
            savedAir = true;
            SimpleAssert.eq(Blocks.air, cell.block(), "air survives registry roundtrip");
            SimpleAssert.that(cell.stack() == null && !cell.missing(), "air has no item cost or missing-block marker");
        }
        SimpleAssert.that(savedAir, "air selection persists in the blueprint");
        Method pin = HologramService.class
            .getDeclaredMethod("pinSelection", type, NBTTagCompound.class, int.class, ItemStack.class);
        pin.setAccessible(true);
        int looseIndex = capture.cells.indexOf(capture.positions.get("4,64,0"));
        int airIndex = capture.cells.indexOf(virtualAir);
        NBTTagCompound selected = new NBTTagCompound();
        selected.setIntArray("selectedIndices", new int[] { looseIndex, airIndex });
        pin.invoke(null, session, selected, looseIndex, new ItemStack(Blocks.planks, 1, 1));
        SimpleAssert.eq(1, capture.positions.get("4,64,0").meta, "candidate updates virtual world metadata");
        SimpleAssert.eq(1, capture.positions.get("19,64,0").meta, "candidate applies to the entire selected batch");
        int structureIndex = capture.cells.indexOf(capture.positions.get("6,64,0"));
        selected.setIntArray("selectedIndices", new int[] { looseIndex, structureIndex });
        ItemStack previous = ((Map<Integer, ItemStack>) get(session, "pins")).get(looseIndex)
            .copy();
        try {
            pin.invoke(null, session, selected, looseIndex, new ItemStack(Blocks.dirt));
            throw new AssertionError("candidate batch must reject a structurally illegal member");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert
                .that(rejected.getCause() instanceof java.io.IOException, "candidate batch validates all members");
        }
        SimpleAssert.that(
            previous.isItemEqual(((Map<Integer, ItemStack>) get(session, "pins")).get(looseIndex)),
            "failed batch cannot partially pin its valid first member");
        HologramPresets.Preset implicit = new HologramPresets.Preset();
        implicit.schema = 2;
        implicit.name = "Implicit legacy template";
        implicit.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, true));
        implicit.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 2, 0, 0, -1, false));
        set(session, "virtualPreset", implicit);
        method(type, "populateVirtual").invoke(null, session);
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.eq(1, implicit.structures.size(), "untagged controller template becomes one explicit structure");
        SimpleAssert.that(
            capture.cells.stream()
                .allMatch(cell -> cell.belongsTo(implicit.structures.get(0).id)),
            "template shell never masquerades as independent loose blocks");
        action.setIntArray("indices", new int[] { 0 });
        action.setInteger("dx", 4);
        try {
            move.invoke(null, session, action);
            throw new AssertionError("partial implicit template cannot move");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert
                .that(rejected.getCause() instanceof java.io.IOException, "implicit template keeps whole constraint");
        }
        action.setIntArray("indices", new int[] { 0, 1 });
        move.invoke(null, session, action);
        SimpleAssert
            .eq(2, ((HologramCapture) get(session, "capture")).cells.size(), "complete implicit template can move");
        implicit.schema = 1;
        try {
            move.invoke(null, session, action);
            throw new AssertionError("schema one templates cannot expose coordinate editing");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert
                .that(rejected.getCause() instanceof java.io.IOException, "schema one coordinate edits fail closed");
        }
        declarationCacheBoundaries(session);
    }

    @SuppressWarnings("unchecked")
    private static void declarationCacheBoundaries(Object session) throws ReflectiveOperationException {
        Class<?> type = session.getClass();
        ((Map<?, ?>) get(session, "declarationCandidates")).clear();
        ((Map<?, ?>) get(session, "pureDeclarations")).clear();
        set(session, "declarationCandidateCount", 0);
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> pure = com.gtnewhorizon.structurelib.structure.StructureUtility
            .withChannel(
                "cache_test",
                com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock(Blocks.stone, 0));
        HologramCapture.Cell first = new HologramCapture.Cell(pure, 31, 64, 0);
        HologramCapture.Cell second = new HologramCapture.Cell(pure, 32, 64, 0);
        Method candidates = HologramService.class
            .getDeclaredMethod("declaredCandidates", type, HologramCapture.Cell.class);
        candidates.setAccessible(true);
        List<ItemStack> one = (List<ItemStack>) candidates.invoke(null, session, first);
        List<ItemStack> two = (List<ItemStack>) candidates.invoke(null, session, second);
        SimpleAssert.eq(
            1,
            get(session, "declarationCandidateCount"),
            "pure declaration and transparent channel reuse one raw candidate cache across coordinates");
        SimpleAssert.that(
            one != two && one.size() == 1 && two.size() == 1,
            "per-position candidate lists remain independent for shared-cell intersections");
        one.clear();
        SimpleAssert.eq(
            1,
            ((List<?>) candidates.invoke(null, session, first)).size(),
            "position intersection cannot mutate cached raw declaration candidates");
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> chain = com.gtnewhorizon.structurelib.structure.StructureUtility
            .ofChain(pure, com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock(Blocks.dirt, 0));
        SimpleAssert.that(!HologramDeclarationPurity.contextFree(chain), "chains retain contextual cache boundaries");
        candidates.invoke(null, session, new HologramCapture.Cell(chain, 33, 64, 0));
        candidates.invoke(null, session, new HologramCapture.Cell(chain, 34, 64, 0));
        SimpleAssert
            .eq(3, get(session, "declarationCandidateCount"), "contextual branches retain separate coordinates");
        set(session, "declarationCandidateCount", 1024);
        candidates.invoke(null, session, first);
        SimpleAssert.eq(1, get(session, "declarationCandidateCount"), "raw candidate cache remains bounded");
    }

    @SuppressWarnings("unchecked")
    private static HologramCapture.Cell addFixtureCell(Object session, int x, String owner, boolean controller)
        throws ReflectiveOperationException {
        HologramCapture capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, 64, 0);
        cell.block = Blocks.stone;
        cell.userPosition = true;
        cell.structureId = owner;
        cell.role = controller ? "templateController" : "prefab";
        int index = capture.cells.size();
        capture.cells.add(cell);
        capture.positions.put(x + ",64,0", cell);
        ((Map<Integer, ItemStack>) get(session, "pins")).put(index, new ItemStack(Blocks.stone));
        ((Map<String, ItemStack>) get(session, "coordinatePins")).put(x + ",64,0", new ItemStack(Blocks.stone));
        return cell;
    }

    @SuppressWarnings("unchecked")
    private static void selectionLifecycle(EntityPlayerMP player, Object session) throws ReflectiveOperationException {
        Field sessionsField = HologramService.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        Map<UUID, Object> sessions = (Map<UUID, Object>) sessionsField.get(null);
        Field areasField = HologramService.class.getDeclaredField("AREAS");
        areasField.setAccessible(true);
        Map<UUID, HologramArea> areas = (Map<UUID, HologramArea>) areasField.get(null);
        ItemStack original = player.getHeldItem();
        ItemStack unrelated = new ItemStack(Blocks.stone);
        unrelated.setTagCompound(new NBTTagCompound());
        unrelated.getTagCompound()
            .setString("gtitHologramToolId", "unrelated-tool");
        unrelated.getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        player.inventory.mainInventory[1] = unrelated;
        original.getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        HologramArea area = new HologramArea();
        area.selecting = true;
        area.point(1, 1, 64, 1);
        sessions.put(player.getUniqueID(), session);
        areas.put(player.getUniqueID(), area);
        SimpleAssert.that(HologramService.isAreaSelecting(player, original), "selection binds its current tool");
        player.inventory.currentItem = 1;
        SimpleAssert.that(
            HologramService.isAreaSelecting(player, original),
            "slot switch preserves the bound area while the tool remains owned");
        SimpleAssert.that(
            !HologramService.isAreaSelecting(player, unrelated),
            "suspended selection never grants another tool its authority");
        selectionNetworkCancellation(player, session, area, areas, original, unrelated);
        player.inventory.currentItem = 0;
        player.dimension++;
        SimpleAssert.that(!HologramService.isAreaSelecting(player, original), "dimension change invalidates selection");
        Method ui = HologramService.class.getDeclaredMethod("uiSession", EntityPlayerMP.class);
        ui.setAccessible(true);
        ui.invoke(null, player);
        SimpleAssert.that(!areas.containsKey(player.getUniqueID()), "new dimension cannot inherit old corners");
        SimpleAssert.that(
            !original.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"),
            "old bound tool marker cleared");
        SimpleAssert.that(
            unrelated.getTagCompound()
                .getBoolean("gtitHologramAreaSelecting"),
            "other logical tool untouched");
        original.getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        area = new HologramArea();
        area.selecting = true;
        areas.put(player.getUniqueID(), area);
        player.inventory.currentItem = 1;
        ui.invoke(null, player);
        SimpleAssert.that(!areas.containsKey(player.getUniqueID()), "tool switch cannot inherit old region");
        SimpleAssert.that(
            !original.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"),
            "switched-away marker cleared");
        areas.put(player.getUniqueID(), area);
        HologramService.clear();
        SimpleAssert.that(
            !unrelated.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"),
            "server clear removes bound mode");
        SimpleAssert
            .that(!HologramService.isAreaSelecting(player, unrelated), "orphan tool marker cannot authorize selection");
    }

    private static void selectionNetworkCancellation(EntityPlayerMP player, Object session, HologramArea area,
        Map<UUID, HologramArea> areas, ItemStack original, ItemStack unrelated) throws ReflectiveOperationException {
        Method dispatch = HologramNetwork.class
            .getDeclaredMethod("dispatch", EntityPlayerMP.class, NBTTagCompound.class);
        dispatch.setAccessible(true);
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previousChannel = channel.get(null);
        Object capture = get(session, "capture"), preset = get(session, "virtualPreset");
        int previousJob = (Integer) get(session, "job");
        int originalCount = original.stackSize, unrelatedCount = unrelated.stackSize;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "areaPoint");
        request.setString("session", (String) get(session, "id"));
        request.setLong("planRevision", (Long) get(session, "planRevision"));
        request.setInteger("point", 2);
        request.setInteger("x", 2);
        request.setInteger("y", 64);
        request.setInteger("z", 2);
        try {
            channel.set(null, null);
            dispatch.invoke(null, player, request);
            SimpleAssert.that(
                area.second == null && areas.get(player.getUniqueID()) == area,
                "network dispatch still rejects area points while a non-projector slot is held");
            request.setString("action", "cancelAreaSelection");
            request.setString("session", "expired-area-session");
            dispatch.invoke(null, player, request);
            SimpleAssert.that(
                areas.get(player.getUniqueID()) == area,
                "a stale cancellation packet cannot discard the suspended area");
            request.setString("session", (String) get(session, "id"));
            for (int job : new int[] { 1, 2 }) {
                set(session, "job", job);
                dispatch.invoke(null, player, request);
                SimpleAssert.that(
                    areas.get(player.getUniqueID()) == area,
                    "network cancellation preserves the existing construction-job guard");
            }
            set(session, "job", 0);
            player.inventory.mainInventory[0] = null;
            dispatch.invoke(null, player, request);
            SimpleAssert.that(
                areas.get(player.getUniqueID()) == area,
                "network cancellation requires the bound physical tool to remain owned");
            player.inventory.mainInventory[0] = original;
            dispatch.invoke(null, player, request);
            SimpleAssert.that(
                !areas.containsKey(player.getUniqueID()),
                "a current cancellation packet reaches Service after switching to another slot");
            SimpleAssert.that(
                !original.getTagCompound()
                    .hasKey("gtitHologramAreaSelecting") && unrelated.getTagCompound()
                        .getBoolean("gtitHologramAreaSelecting"),
                "suspended cancellation clears only the bound tool's marker");
            SimpleAssert.that(
                capture == get(session, "capture") && preset == get(session, "virtualPreset"),
                "network cancellation retains the exact blueprint and capture");
            SimpleAssert.that(
                player.inventory.currentItem == 1 && original.stackSize == originalCount
                    && unrelated.stackSize == unrelatedCount,
                "network cancellation preserves the selected slot and material counts");
        } finally {
            channel.set(null, previousChannel);
            set(session, "job", previousJob);
            player.inventory.mainInventory[0] = original;
            original.getTagCompound()
                .setBoolean("gtitHologramAreaSelecting", true);
            areas.put(player.getUniqueID(), area);
        }
    }

    private static Method method(Class<?> type, String name) throws ReflectiveOperationException {
        Method method = HologramService.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method;
    }

    private static Object get(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws ReflectiveOperationException {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
