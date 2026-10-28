package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.common.blocks.ItemMachines;

/** Native author adapters operating only on detached records; no player, world, session or inventory mutation. */
public final class HologramLocalAuthorConfigurations {

    private static final Gson GSON = new Gson();

    private HologramLocalAuthorConfigurations() {}

    public static JsonObject invalidateHeader(JsonObject header, String dependency) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Preset metadata = HologramLocalAuthorSemantics.preset(header);
            HologramBlueprintProcessing.invalidate(metadata, dependency);
            JsonObject patch = new JsonObject();
            patch.add("processingNbt", GSON.toJsonTree(metadata.processingNbt));
            return patch;
        }
    }

    public static JsonObject deidentify(JsonObject source, int level) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Preset before = HologramLocalAuthorSemantics.preset(source);
            Map<String, String> detachedNetworks = java.util.Collections.emptyMap();
            HologramPresets.Preset after = HologramDeidentification.applyOwned(
                HologramPresets.copy(before),
                level,
                detachedNetworks,
                HologramAePolicy.PreparationProgress.NONE);
            after = HologramAePolicy
                .sanitizeOwned(after, detachedNetworks, HologramAePolicy.PreparationProgress.NONE).preset;
            after.deidentificationChosen = true;
            HologramBlueprintProcessing.invalidate(after, "identity");
            return merge(source, before, after);
        }
    }

    public static JsonObject networkHint(JsonObject source, String group, String hint) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Preset changed = HologramLocalAuthorSemantics.preset(source);
            JsonElement before = GSON.toJsonTree(changed);
            HologramNetworkBindings.editHint(changed, group, hint);
            return HologramLocalAuthorSemantics.delta(source, before, GSON.toJsonTree(changed))
                .getAsJsonObject();
        }
    }

    /** Null means not a member; only matched records are retained by the caller's sparse transaction. */
    public static JsonObject networkCellHint(JsonObject source, String group, String hint) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramNetworkBindings.checkedHint(hint);
            HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey(HologramNetworkBindings.GROUP)
                || !group.equals(HologramNetworkBindings.group(tag))) return null;
            JsonElement before = GSON.toJsonTree(cell);
            NBTTagCompound previous = (NBTTagCompound) tag.copy();
            tag.setString(HologramNetworkBindings.HINT, hint);
            NBTTagCompound projected = HologramBlueprintNbt.canonicalActorPayload(cell.block(), tag, null, "", false);
            if (!previous.equals(projected)) cell.tileNbt = HologramBlueprintNbt.encodeCanonical(projected);
            return HologramLocalAuthorSemantics.delta(source, before, GSON.toJsonTree(cell))
                .getAsJsonObject();
        }
    }

    /** Descriptor-only view. Native network ownership and real terminal availability are deliberately unknown. */
    public static NBTTagCompound networks(JsonObject source) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            List<JsonObject> cells = new ArrayList<>();
            if (source.has("cells"))
                for (JsonElement cell : source.getAsJsonArray("cells")) cells.add(cell.getAsJsonObject());
            return networks(cells);
        }
    }

    /** Streaming descriptor read; only aggregate group rows survive each record. */
    public static NBTTagCompound networks(Iterable<JsonObject> sourceCells) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            Map<String, NBTTagCompound> groups = new LinkedHashMap<>();
            boolean terminalBinding = false;
            for (JsonObject source : sourceCells) {
                HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
                if (cell.excluded) continue;
                NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
                if (tag == null || !tag.hasKey(HologramNetworkBindings.GROUP)) continue;
                String group = HologramNetworkBindings.group(tag);
                NBTTagCompound row = groups.get(group);
                if (row == null) {
                    row = new NBTTagCompound();
                    row.setString("id", group);
                    row.setString("hint", HologramNetworkBindings.hint(tag));
                    row.setString(
                        "prompt",
                        HologramNetworkBindings.checkedHint(tag.getString(HologramNetworkBindings.HINT)));
                    row.setString(
                        "name",
                        row.getString("prompt")
                            .isEmpty() ? "默认网络" : row.getString("prompt"));
                    row.setBoolean(
                        "terminalBinding",
                        !"preserve".equals(tag.getString(HologramNetworkBindings.MODE))
                            && !tag.hasKey(HologramNetworkBindings.ORIGINAL));
                    row.setString("reason", "本地编辑仅修改蓝图；实际终端和网络身份在放置时由服务端核验");
                    row.setBoolean("bound", false);
                    groups.put(group, row);
                }
                row.setInteger("cells", row.getInteger("cells") + 1);
                terminalBinding |= row.getBoolean("terminalBinding");
            }
            NBTTagCompound result = new NBTTagCompound();
            NBTTagList rows = new NBTTagList();
            for (NBTTagCompound row : groups.values()) rows.appendTag(row);
            result.setTag("groups", rows);
            result.setTag("inventory", new NBTTagList());
            result.setBoolean("terminalBinding", terminalBinding);
            result.setBoolean("nativeAuthority", false);
            result.setBoolean("terminalSecurityChecked", false);
            result.setBoolean("localDescriptors", true);
            return result;
        }
    }

    /** The report is recalculated from this exact source, never trusted from client-supplied row coordinates. */
    public static JsonObject excludeBlockers(JsonObject source, boolean confirmDiscard) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            if (!confirmDiscard) throw new IOException("请先确认排除当前报告中的阻塞蓝图格");
            HologramPresets.Preset input = HologramLocalAuthorSemantics.preset(source);
            JsonElement before = GSON.toJsonTree(input);
            HologramAePolicy.Draft report = HologramAePolicy.sanitizeOwned(
                HologramPresets.copy(input),
                java.util.Collections.emptyMap(),
                HologramAePolicy.PreparationProgress.NONE);
            Map<String, HologramPresets.Cell> positions = new LinkedHashMap<>();
            for (HologramPresets.Cell cell : input.cells) positions.put(cell.x + "," + cell.y + "," + cell.z, cell);
            for (HologramAePolicy.Issue issue : report.issues) if ("blocker".equals(issue.severity)) {
                HologramPresets.Cell cell = positions.get(issue.x + "," + issue.y + "," + issue.z);
                if (cell != null && !cell.controller) cell.excluded = true;
            }
            return HologramLocalAuthorSemantics.delta(source, before, GSON.toJsonTree(input))
                .getAsJsonObject();
        }
    }

    public static List<ItemStack> candidates(JsonObject source) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
            List<ItemStack> result = new ArrayList<>();
            if (cell.controller) return result;
            if (cell.allowedTargets != null) for (HologramPresets.Target target : cell.allowedTargets) {
                ItemStack stack = target.stack();
                if (HologramNativePlacement.supported(cell.block())) {
                    ItemStack sample = HologramNativePlacement
                        .sample(cell.block(), cell.metadata, HologramBlueprintNbt.decode(cell.tileNbt));
                    stack = HologramValues.sameMaterial(stack, sample) ? sample : null;
                }
                if (stack != null && acceptable(cell, stack)) result.add(stack.copy());
            }
            else {
                ItemStack stack = cell.stack();
                if (stack != null && acceptable(cell, stack)) result.add(stack.copy());
            }
            return result;
        }
    }

    public static int[] legalFacings(JsonObject source) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
            return legalFacings(cell);
        }
    }

    private static int[] legalFacings(HologramPresets.Cell cell) {
        ItemStack target = cell.stack();
        if (cell.controller || target == null || !(target.getItem() instanceof ItemMachines)) return new int[0];
        IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(target);
        if (prototype == null || prototype instanceof MetaPipeEntity) return new int[0];
        List<Integer> faces = new ArrayList<>();
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS)
            if (prototype.isFacingValid(face)) faces.add(face.ordinal());
        int[] result = new int[faces.size()];
        for (int i = 0; i < result.length; i++) result[i] = faces.get(i);
        return result;
    }

    public static JsonObject facing(JsonObject source, int facing, boolean cycle) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
            if (cell.controller) throw new IOException("控制器受保护");
            JsonElement before = GSON.toJsonTree(cell);
            ItemStack target = cell.stack();
            if (cycle && (HologramBlockTransforms.isPipe(target) || target == null
                || !(target.getItem() instanceof ItemMachines))) {
                HologramBlockTransforms.turn(cell, false, null, true);
                HologramBlockTransforms.nativeSample(cell);
            } else {
                int[] legal = legalFacings(cell);
                int next = cycle ? HologramCellRotation.nextFacing(cell.facing, legal) : facing;
                boolean accepted = next == -1 && !cycle;
                for (int allowed : legal) accepted |= next == allowed;
                if (!accepted) throw new IOException("本格目标不支持所选朝向");
                cell.facing = next;
            }
            return HologramLocalAuthorSemantics.delta(source, before, GSON.toJsonTree(cell))
                .getAsJsonObject();
        }
    }

    public static JsonObject restore(JsonObject source) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
            ItemStack target = cell.defaultStack();
            if (target == null && cell.block() != null && cell.block() != Blocks.air) {
                ItemStack original = new ItemStack(cell.block(), 1, cell.metadata);
                if (!(original.getItem() instanceof ItemMachines)) target = original;
            }
            JsonObject result = target(source, target);
            result.addProperty("facing", -1);
            return result;
        }
    }

    /** Inventory samples have no captured world frame; never silently inherit the destination's provenance. */
    public static JsonObject targetExternal(JsonObject source, ItemStack selected, boolean payloadPositionPending)
        throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            JsonObject result = target(source, selected);
            HologramPresets.Cell before = HologramLocalAuthorSemantics.cell(source);
            HologramPresets.Cell after = HologramLocalAuthorSemantics.cell(result);
            if (after.itemNbt == null || after.itemNbt.isEmpty()
                || java.util.Objects.equals(before.itemNbt, after.itemNbt)) return result;
            // Inspect only the newly introduced item scope; retained tile payload keeps its existing source frame.
            after.tileNbt = null;
            after.excluded = false;
            after.structureId = null;
            after.structureIds = java.util.Collections.emptyList();
            HologramPresets.Preset sample = new HologramPresets.Preset();
            sample.schema = 2;
            sample.cells.add(after);
            try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
                for (HologramCoordinatePolicies.Relation relation : HologramCoordinatePolicies
                    .inspect(sample, 0, 0, 0, 0, facts)) {
                    if (relation.previousMode == null) throw new IOException("背包样本含缺少来源坐标框架的原始世界链接，不能继承当前蓝图来源；原稿保留");
                    if (payloadPositionPending && "relative".equals(relation.previousMode))
                        throw new IOException("请先兑现当前格的位置变换，再采用含相对链接的外来样本；原稿保留");
                }
            }
            return result;
        }
    }

    public static JsonObject target(JsonObject source, ItemStack selected) throws IOException {
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
            if (!acceptable(cell, selected)) throw new IOException("目标不符合本格原生声明或可放置样本");
            ItemStack target = selected.copy();
            target.stackSize = 1;
            JsonElement before = GSON.toJsonTree(cell);
            Block block = Block.getBlockFromItem(target.getItem());
            int metadata = target.getItem()
                .getMetadata(target.getItemDamage());
            if (block == cell.block() && target.getItem() instanceof ItemMachines) metadata = cell.metadata;
            else if (target.getItem() instanceof ItemBlock && cell.block() != null) {
                try {
                    HologramPresets.validateMaterialVariant(cell.block(), cell.metadata, target);
                    block = cell.block();
                    metadata = cell.metadata;
                } catch (IOException differentVariant) { /* Explicit replacement adopts its own native metadata. */ }
            }
            if (HologramNativePlacement.supported(cell.block())) {
                block = cell.block();
                metadata = cell.metadata;
            }
            String item = String.valueOf(Item.itemRegistry.getNameForObject(target.getItem()));
            if (block != cell.block() || metadata != cell.metadata
                || !item.equals(cell.item)
                || target.getItemDamage() != cell.damage) {
                cell.tileNbt = "";
                cell.itemNbt = "";
                cell.facing = -1;
            }
            cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
            cell.metadata = metadata;
            cell.item = item;
            cell.damage = target.getItemDamage();
            if ((cell.itemNbt == null || cell.itemNbt.isEmpty()) && target.hasTagCompound())
                cell.itemNbt = HologramBlueprintNbt.encodeCaptured(block, target.getTagCompound());
            return HologramLocalAuthorSemantics.delta(source, before, GSON.toJsonTree(cell))
                .getAsJsonObject();
        }
    }

    private static boolean acceptable(HologramPresets.Cell cell, ItemStack sample) throws IOException {
        if (cell.controller || sample == null
            || sample.getItem() == null
            || sample.stackSize < 1
            || sample.getItemDamage() < 0
            || HologramCellPolicy.hintMaterial(sample)
            || HologramRecovery.hasSeal(sample)) return false;
        if (sample.getItem() instanceof ItemMachines) {
            int id = sample.getItemDamage();
            if (id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] == null) return false;
        } else if (HologramNativePlacement.supported(cell.block())) {
            if (!HologramNativePlacement
                .matchesSample(cell.block(), cell.metadata, HologramBlueprintNbt.decode(cell.tileNbt), sample))
                return false;
        } else if (!(sample.getItem() instanceof ItemBlock) || Block.getBlockFromItem(sample.getItem()) == Blocks.air)
            return false;
        if (cell.allowedTargets == null) return true;
        for (HologramPresets.Target allowed : cell.allowedTargets)
            if (HologramValues.sameMaterial(allowed.stack(), sample)) return true;
        // Replay the exact stored shell, never enlarge a captured declaration with an unrelated target.
        if (!HologramValues.sameMaterial(cell.stack(), sample)) return false;
        if (sample.getItem() instanceof ItemMachines) return true;
        if (sample.hasTagCompound() || cell.tileNbt != null && !cell.tileNbt.isEmpty()
            || cell.itemNbt != null && !cell.itemNbt.isEmpty()
            || cell.block() == null
            || cell.block()
                .hasTileEntity(cell.metadata))
            return false;
        HologramPresets.validateMaterialVariant(cell.block(), cell.metadata, sample, null);
        return true;
    }

    private static JsonObject merge(JsonObject source, Object before, Object after) throws IOException {
        return HologramLocalAuthorSemantics.delta(source, GSON.toJsonTree(before), GSON.toJsonTree(after))
            .getAsJsonObject();
    }
}
