package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.hologram.HologramLocalAuthorConfigurations;

/** Typed document commands. Player inventory is at most a copied sample, never a payment or target inventory. */
final class HologramLocalAuthorConfigurationActions {

    private HologramLocalAuthorConfigurationActions() {}

    static boolean execute(HologramLocalAuthorRuntime runtime, HologramUiModules ui, String operation,
        NBTTagCompound supplied) {
        if (!handles(operation)) return false;
        NBTTagCompound request = supplied == null ? new NBTTagCompound() : supplied;
        try {
            if (!runtime.editable()) throw new IOException(runtime.diagnostic());
            long revision = runtime.document()
                .revision();
            if (request.hasKey("planRevision", 4) && request.getLong("planRevision") != revision)
                throw new IOException("文档已变化，请重新检查当前操作");
            if ("closeNetworkBindings".equals(operation)) return true;
            if ("openNetworkBindings".equals(operation)) {
                refreshNetworks(runtime);
                return true;
            }
            if ("editBlueprintNetworkHint".equals(operation)) {
                Map<Long, JsonObject> replacements = new LinkedHashMap<>();
                for (AuthorDocument.Cell cell : runtime.document()
                    .cells()) {
                    JsonObject changed = HologramLocalAuthorConfigurations
                        .networkCellHint(cell.sourceCopy(), request.getString("group"), request.getString("hint"));
                    if (changed != null) replacements.put(cell.id(), changed);
                }
                if (replacements.isEmpty()) throw new IOException("未知蓝图量子类群");
                NBTTagCompound networks = HologramLocalAuthorConfigurations.networks(records(runtime, replacements));
                runtime.apply(
                    runtime.editor()
                        .replaceCells(
                            replacements,
                            HologramLocalAuthorConfigurations.invalidateHeader(
                                runtime.document()
                                    .headerCopy(),
                                "prompt")));
                publishNetworks(runtime, networks);
                return true;
            }
            if ("deidentifyBlueprint".equals(operation) || "resetBlueprintNetworks".equals(operation)) {
                JsonObject changed = HologramLocalAuthorConfigurations.deidentify(
                    runtime.sourceJson(),
                    "resetBlueprintNetworks".equals(operation) ? 1 : request.getInteger("level"));
                NBTTagCompound networks = HologramLocalAuthorConfigurations.networks(changed);
                runtime.commitSource(revision, changed);
                publishNetworks(runtime, networks);
                return true;
            }
            if ("excludeBlueprintBlockers".equals(operation)) {
                if (!request.hasKey("planRevision", 4)) throw new IOException("请重新查看当前阻塞报告后确认排除");
                runtime.commitSource(
                    revision,
                    HologramLocalAuthorConfigurations
                        .excludeBlockers(runtime.sourceJson(), request.getBoolean("confirmDiscard")));
                return true;
            }
            if ("configure".equals(operation)) {
                configure(runtime, ui, request);
                return true;
            }
            int index = request.getInteger("selected");
            int[] indices = request.hasKey("indices", 11) ? request.getIntArray("indices")
                : request.hasKey("selectedIndices", 11) ? request.getIntArray("selectedIndices") : new int[] { index };
            if (indices.length == 0) indices = new int[] { index };
            Set<Long> selected = runtime.selectionIds(indices);
            if (selected.isEmpty() || selected.size() > 8192) throw new IOException("请选择有效的蓝图格");
            ItemStack sample = null;
            if ("pin".equals(operation)) {
                long primary = runtime.selectionIds(new int[] { index })
                    .iterator()
                    .next();
                List<ItemStack> options = HologramLocalAuthorConfigurations.candidates(runtime.sourceCell(primary));
                int choice = request.hasKey("choiceindex", 3) ? request.getInteger("choiceindex")
                    : request.getInteger("choice");
                if (choice < 0 || choice >= options.size()) throw new IOException("候选目标已变化，请重新选择");
                sample = options.get(choice);
            } else if ("sourcePin".equals(operation)) {
                sample = HologramLocalAuthorMaterialCatalog.sample(runtime, request.getInteger("choice"));
            } else if ("customPin".equals(operation)) {
                int slot = request.getInteger("inventorySlot");
                if (ui.host.minecraft().thePlayer == null || slot < 0
                    || slot >= ui.host.minecraft().thePlayer.inventory.mainInventory.length)
                    throw new IOException("背包样本已不可用");
                ItemStack found = ui.host.minecraft().thePlayer.inventory.mainInventory[slot];
                if (found == null) throw new IOException("背包样本已不可用");
                sample = found.copy();
            }
            Map<Long, JsonObject> replacements = new LinkedHashMap<>();
            String active = runtime.view().data.getString("activeStructure");
            for (long id : selected) {
                AuthorDocument.Cell cell = runtime.document()
                    .cell(id);
                if (!active.isEmpty() && !cell.memberships()
                    .isEmpty()
                    && !cell.memberships()
                        .contains(active))
                    throw new IOException("该格属于其他结构，请先切换结构");
                JsonObject source = cell.sourceCopy();
                JsonObject changed;
                if ("pin".equals(operation) || "customPin".equals(operation) || "sourcePin".equals(operation))
                    changed = HologramLocalAuthorConfigurations.target(source, sample);
                else if ("restoreCell".equals(operation) || "clearPin".equals(operation))
                    changed = HologramLocalAuthorConfigurations.restore(source);
                else changed = HologramLocalAuthorConfigurations
                    .facing(source, request.getInteger("facing"), "rotateCell".equals(operation));
                replacements.put(id, changed);
            }
            runtime.editor()
                .select(selected);
            runtime.apply(
                runtime.editor()
                    .replaceCells(
                        replacements,
                        HologramLocalAuthorConfigurations.invalidateHeader(
                            runtime.document()
                                .headerCopy(),
                            "geometry")));
            ui.session.notice("本地目标已更新；未移动真实物品");
        } catch (IOException | RuntimeException failure) {
            ui.session.notice("本地配置未应用，原稿保留：" + failure.getMessage());
        }
        return true;
    }

    static void refreshNetworks(HologramLocalAuthorRuntime runtime) throws IOException {
        publishNetworks(
            runtime,
            HologramLocalAuthorConfigurations.networks(records(runtime, java.util.Collections.emptyMap())));
    }

    private static Iterable<JsonObject> records(HologramLocalAuthorRuntime runtime,
        Map<Long, JsonObject> replacements) {
        return () -> new java.util.Iterator<JsonObject>() {

            private final java.util.Iterator<AuthorDocument.Cell> source = runtime.document()
                .cells()
                .iterator();

            public boolean hasNext() {
                return source.hasNext();
            }

            public JsonObject next() {
                AuthorDocument.Cell cell = source.next();
                return replacements.containsKey(cell.id()) ? replacements.get(cell.id()) : cell.sourceCopy();
            }
        };
    }

    private static void publishNetworks(HologramLocalAuthorRuntime runtime, NBTTagCompound networks) {
        NBTTagCompound view = new NBTTagCompound();
        view.setTag("networkBindings", networks);
        runtime.setViewMetadata(view);
    }

    private static void configure(HologramLocalAuthorRuntime runtime, HologramUiModules ui, NBTTagCompound request)
        throws IOException {
        String active = runtime.view().data.getString("activeStructure");
        if (request.hasKey("structureId", 8) && !active.equals(request.getString("structureId")))
            throw new IOException("结构选择已变化，请重新打开参数面板");
        if (request.getInteger("mode") < 0 || request.getInteger("mode") > 1
            || request.getInteger("scope") < 0
            || request.getInteger("scope") > 3
            || request.getInteger("layer") < -128
            || request.getInteger("layer") > 128) throw new IOException("本地工作区参数无效；实体拆除必须使用服务端工作流");
        NBTTagCompound current = runtime.view().data;
        boolean sourceSettingsChanged = false;
        for (String key : new String[] { "materialMain", "materialContainers", "materialMe" })
            sourceSettingsChanged |= request.hasKey(key, 1) && request.getBoolean(key) != current.getBoolean(key);
        sourceSettingsChanged |= request.hasKey("materialPriority", 3)
            && request.getInteger("materialPriority") != current.getInteger("materialPriority");
        if (sourceSettingsChanged && request.getBoolean("authorMaterialSettings")) {
            HologramLocalAuthorMaterialCatalog.configure(runtime, ui.session, request);
            return;
        }
        if (request.hasKey("hatchProfile", 10) || request.getBoolean("applyHatchPreset"))
            throw new IOException("仓室搭配需要独立的原生配额规划，不能当作信道元数据直接写入");
        if (request.hasKey("main", 3) && request.getInteger("main") != current.getInteger("main")
            || request.hasKey("channels", 10) && !request.getCompoundTag("channels")
                .equals(current.getCompoundTag("channels"))
            || request.hasKey("noHatches", 1) && request.getBoolean("noHatches") != current.getBoolean("noHatches")) {
            HologramLocalAuthorStructureActions.start(runtime, ui, request);
            return;
        }
        NBTTagCompound view = new NBTTagCompound();
        for (String key : new String[] { "mode", "scope", "layer" })
            if (request.hasKey(key, 3)) view.setInteger(key, request.getInteger(key));
        if (request.hasKey("selectedIndices", 11)) runtime.select(request.getIntArray("selectedIndices"));
        else if (request.hasKey("selected", 3) && request.getInteger("selected") >= 0)
            runtime.select(new int[] { request.getInteger("selected") });
        runtime.setViewMetadata(view);
    }

    private static boolean handles(String operation) {
        return "pin".equals(operation) || "customPin".equals(operation)
            || "sourcePin".equals(operation)
            || "clearPin".equals(operation)
            || "restoreCell".equals(operation)
            || "rotateCell".equals(operation)
            || "hatchFacing".equals(operation)
            || "configure".equals(operation)
            || "deidentifyBlueprint".equals(operation)
            || "resetBlueprintNetworks".equals(operation)
            || "excludeBlueprintBlockers".equals(operation)
            || "openNetworkBindings".equals(operation)
            || "closeNetworkBindings".equals(operation)
            || "editBlueprintNetworkHint".equals(operation);
    }
}
