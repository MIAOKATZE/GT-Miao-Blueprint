package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramObservedItemBindings;

import gregtech.api.enums.ItemList;

/** Version-bound, read-only observations from the owned native editor fixture. Never reads the player's library. */
final class HologramEditorTutorialSamples {

    enum Kind {
        PHYSICAL,
        AUTHOR,
        MEMBER,
        OVERVIEW
    }

    private static final String ROOT = "/assets/gtmb/tutorial/";
    private static final Map<Kind, Sample> CACHE = new HashMap<>();
    private static Properties manifest;

    private HologramEditorTutorialSamples() {}

    /** Library documents and the initially empty editor have independent ownership in teaching memory. */
    static NBTTagCompound blueprintDocument(String id, String name) throws IOException {
        NBTTagCompound data = planning(true);
        data.setString("selectedPresetId", id);
        data.setString("virtualPresetId", id);
        data.setString("blueprintSavedId", id);
        data.setString("virtualPresetName", name);
        data.setString("title", "两台电力高炉 · 教学起始规划");
        data.setString("blueprintSource", "saved");
        data.setString("openPurpose", "blueprintEdit");
        data.setString("editorWorkspace", "blueprint");
        data.setBoolean("blueprintSaved", true);
        data.setBoolean("blueprintDirty", false);
        return data;
    }

    static NBTTagCompound openBlueprintDocument(NBTTagCompound document, NBTTagCompound previous) {
        NBTTagCompound data = (NBTTagCompound) document.copy();
        data.setString("session", previous.getString("session"));
        data.setTag(
            "presets",
            previous.getTagList("presets", 10)
                .copy());
        data.setLong("planRevision", previous.getLong("planRevision") + 1);
        data.setString(
            "tutorialStructureId",
            data.getTagList("structures", 10)
                .getCompoundTagAt(0)
                .getString("id"));
        data.setString("activeStructure", "");
        data.setBoolean("discardConfirmationRequired", false);
        data.removeTag("discardTargetAction");
        data.setString("status", "教学蓝图已载入；仅在隔离内存中编辑。");
        return data;
    }

    /** Clear captured workflow identity as well as geometry; an empty author workspace has no loaded document. */
    static NBTTagCompound emptyBlueprint(NBTTagCompound source) {
        NBTTagCompound data = (NBTTagCompound) source.copy();
        for (String key : new String[] { "cells", "structures", "capabilities", "processingNbtKeys",
            "processingNbtPaths", "processingRelations", "blueprintResources", "cellNbtEntries" })
            data.setTag(key, new NBTTagList());
        for (String key : new String[] { "selectedPresetId", "virtualPresetId", "blueprintSavedId", "libraryLocalId",
            "virtualPresetName", "activeStructure", "tutorialStructureId", "openPurpose" }) data.setString(key, "");
        for (String key : new String[] { "processing", "processingPolicy", "tutorialMemberMetadata",
            "tutorialOverviewMetadata", "networkBindings", "materialQuote", "cellNbtOverrides" }) data.removeTag(key);
        for (String key : new String[] { "blueprintDirty", "blueprintSaved", "blueprintProcessingRequired",
            "blueprintProcessingComplete", "blueprintSavePending", "blueprintAwaitingConfirmation",
            "discardConfirmationRequired", "presetActive", "virtualPlacement", "blueprintPreview", "virtualLocked",
            "wholeBlueprintPlacement", "target" }) data.setBoolean(key, false);
        data.setBoolean("virtual", true);
        data.setString("editorWorkspace", "blueprint");
        data.setString("workspaceTab", "blueprint");
        data.setString("blueprintSource", "author");
        data.setInteger("cellCount", 0);
        data.setInteger("selected", -1);
        data.setIntArray("selectedIndices", new int[0]);
        data.setLong("planRevision", 0);
        return data;
    }

    static synchronized NBTTagCompound snapshot(Kind kind) throws IOException {
        return HologramObservedItemBindings.restore(sample(kind).data);
    }

    /** Encoded observation for schema-only regression inspection; never a live UI state. */
    static synchronized NBTTagCompound portableObservation(Kind kind) throws IOException {
        return (NBTTagCompound) sample(kind).data.copy();
    }

    /** Authored arrangement derived from one observed 36-cell EBF, never represented as a multi-machine capture. */
    static NBTTagCompound planning(boolean blueprint) throws IOException {
        NBTTagCompound data = snapshot(blueprint ? Kind.AUTHOR : Kind.PHYSICAL);
        String primary = primaryStructure(Kind.AUTHOR);
        NBTTagList original = data.getTagList("cells", 10), machine = new NBTTagList();
        for (int i = 0; i < original.tagCount(); i++) {
            NBTTagCompound cell = original.getCompoundTagAt(i);
            if (blueprint && !primary.equals(cell.getString("structureId"))) continue;
            NBTTagCompound copy = (NBTTagCompound) cell.copy();
            copy.setBoolean("hasPin", false);
            copy.setInteger("chosenChoice", -1);
            if ("hatch".equals(copy.getString("role"))) copy.setInteger(
                "targetFacing",
                copy.getInteger("hintIndex") == 1 ? 1
                    : copy.getInteger("dz") == 0 ? 2
                        : copy.getInteger("dz") == 2 ? 3 : copy.getInteger("dx") > 0 ? 5 : 4);
            // The physical legacy target is block metadata; actualStack is the observed controller device.
            if (copy.getBoolean("anchor") && copy.hasKey("actualStack", 10)) {
                copy.setTag(
                    "targetStack",
                    copy.getCompoundTag("actualStack")
                        .copy());
                copy.setString(
                    "exactIdentity",
                    "gregtech:gt.blockmachines@" + copy.getCompoundTag("actualStack")
                        .getShort("Damage"));
            }
            if ("hatch".equals(copy.getString("role"))) HologramObservedItemBindings.ebfCandidates(copy);
            if (copy.hasKey("targetStack", 10))
                target(copy, ItemStack.loadItemStackFromNBT(copy.getCompoundTag("targetStack")));
            machine.appendTag(copy);
        }
        require(machine.tagCount() == 36, "The observed EBF footprint changed");
        // Use registered native devices, keeping all item metadata and tags supplied by GT itself.
        ItemStack[] devices = { ItemList.Hatch_Input_Bus_LV.get(1), ItemList.Hatch_Output_Bus_LV.get(1),
            ItemList.Hatch_Energy_LV.get(1), ItemList.Hatch_Maintenance.get(1), ItemList.Hatch_Input_LV.get(1),
            ItemList.Hatch_Output_LV.get(1) };
        int assigned = 0;
        for (int i = 0; i < machine.tagCount(); i++) {
            NBTTagCompound cell = machine.getCompoundTagAt(i);
            if (cell.getInteger("dy") == 0 && "hatch".equals(cell.getString("role")) && assigned < devices.length)
                target(cell, devices[assigned++]);
        }
        require(assigned == devices.length, "The EBF has insufficient bottom hatch positions");
        return arrange(data, machine, blueprint, primary);
    }

    /** Shared with geometry regressions; device normalization above remains bound to the native GT registry. */
    static NBTTagCompound arrange(NBTTagCompound data, NBTTagList machine, boolean blueprint, String primary) {
        NBTTagList cells = new NBTTagList(), structures = new NBTTagList();
        int[][] offsets = blueprint ? new int[][] { { 0, 0, 0 }, { -4, 4, 6 } }
            : new int[][] { { 0, 0, 0 } };
        NBTTagCompound declared = blueprint ? data.getTagList("structures", 10)
            .getCompoundTagAt(0) : null;
        for (int m = 0; m < offsets.length; m++) {
            int[] offset = offsets[m];
            String member = m == 0 ? primary : "tutorial-ebf-" + m;
            if (blueprint) {
                NBTTagCompound declaration = (NBTTagCompound) declared.copy();
                declaration.setString("id", member);
                declaration.setString("name", "电力高炉 " + (m + 1) + (m == 1 ? " · 平台" : " · 地面"));
                declaration.setString("tutorialObservedSourceId", primary);
                for (int axis = 0; axis < 3; axis++) {
                    String key = new String[] { "x", "y", "z" }[axis];
                    declaration.setInteger(key, data.getInteger(key) + offset[axis]);
                }
                structures.appendTag(declaration);
            }
            for (int i = 0; i < machine.tagCount(); i++) {
                NBTTagCompound cell = (NBTTagCompound) machine.getCompoundTagAt(i)
                    .copy();
                for (int axis = 0; axis < 3; axis++) {
                    String key = new String[] { "x", "y", "z" }[axis];
                    cell.setInteger(key, cell.getInteger(key) + offset[axis]);
                    cell.setInteger("d" + key, cell.getInteger("d" + key) + offset[axis]);
                }
                if (blueprint) {
                    cell.setString("structureId", member);
                    NBTTagList memberships = new NBTTagList();
                    memberships.appendTag(new NBTTagString(member));
                    cell.setTag("structureIds", memberships);
                }
                cells.appendTag(cell);
            }
        }
        if (blueprint) {
            // A real block platform, four supporting pillars and an access stair; no decorative stand-ins for machines.
            for (int x = -6; x <= -2; x++) for (int z = 5; z <= 9; z++)
                cells.appendTag(buildingCell(data, x, 3, z, new ItemStack(Blocks.stonebrick)));
            for (int x : new int[] { -6, -2 }) for (int z : new int[] { 5, 9 }) for (int y = 0; y < 3; y++)
                cells.appendTag(buildingCell(data, x, y, z, new ItemStack(Blocks.stonebrick)));
            for (int y = 0; y < 4; y++) {
                for (int support = 0; support < y; support++)
                    cells.appendTag(buildingCell(data, 2 - y, support, 7, new ItemStack(Blocks.stonebrick)));
                cells.appendTag(buildingCell(data, 2 - y, y, 7, new ItemStack(Blocks.stone_brick_stairs, 1, 1)));
            }
            // Reserve a grounded lane for moving the first machine and a separate bay for its later copy.
            for (int x = -6; x <= 4; x++) for (int z = -1; z <= 9; z++)
                cells.appendTag(buildingCell(data, x, -1, z, new ItemStack(Blocks.stonebrick)));
        }
        data.setTag("cells", cells);
        data.setTag("structures", structures);
        data.setIntArray("selectedIndices", new int[0]);
        data.setInteger("selected", -1);
        for (String key : new String[] { "textPlacementAck", "cellNbtOverrides", "cellNbtEntries", "materialQuote",
            "processingRelations" }) data.removeTag(key);
        data.setString(
            "tutorialDerivation",
            "one observed 36-cell EBF; native GT hatch plan; translated copies and native building blocks");
        data.setInteger("cellCount", cells.tagCount());
        updateMaterials(data);
        return data;
    }

    static boolean applyHatchPreset(NBTTagCompound data, int tier, boolean downgrade) {
        String[] roles = { "InputBus", "OutputBus", "Energy", "Maintenance", "InputHatch", "OutputHatch" };
        java.util.List<String> remaining = new java.util.ArrayList<>(java.util.Arrays.asList(roles));
        NBTTagList cells = data.getTagList("cells", 10);
        java.util.List<NBTTagCompound> positions = new java.util.ArrayList<>();
        java.util.List<ItemStack> targets = new java.util.ArrayList<>();
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (!cell.getBoolean("hasPin")) continue;
            ItemStack pinned = ItemStack.loadItemStackFromNBT(cell.getCompoundTag("targetStack"));
            if (pinned == null) return false;
            NBTTagList candidates = cell.getTagList("candidates", 10), names = cell.getTagList("candidateRoles", 8);
            for (int c = 0; c < candidates.tagCount() && c < names.tagCount(); c++) {
                ItemStack candidate = ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(c));
                if (candidate != null && pinned.isItemEqual(candidate)
                    && ItemStack.areItemStackTagsEqual(pinned, candidate)) {
                    remaining.remove(names.getStringTagAt(c));
                    break;
                }
            }
        }
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (!"hatch".equals(cell.getString("role")) || cell.getBoolean("hasPin")) continue;
            String role = cell.getInteger("hintIndex") == 1 ? "Muffler"
                : cell.getInteger("dy") == 0 && !remaining.isEmpty() ? remaining.remove(0) : "";
            if (role.isEmpty()) continue;
            ItemStack stack = HologramObservedItemBindings.chooseObservedHatch(cell, role, tier, downgrade);
            if (stack == null) return false;
            positions.add(cell);
            targets.add(stack);
        }
        if (!remaining.isEmpty()) return false;
        for (int i = 0; i < positions.size(); i++) target(positions.get(i), targets.get(i));
        data.setBoolean("presetActive", true);
        data.setBoolean("presetSupported", true);
        data.setString("presetReason", "教学空库存 · 按 EBF 声明配额与原生候选生成；实际施工仍需服务端校验");
        return true;
    }

    private static NBTTagCompound buildingCell(NBTTagCompound data, int x, int y, int z, ItemStack stack) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("dx", x);
        cell.setInteger("dy", y);
        cell.setInteger("dz", z);
        cell.setInteger("x", data.getInteger("x") + x);
        cell.setInteger("y", data.getInteger("y") + y);
        cell.setInteger("z", data.getInteger("z") + z);
        cell.setString("id", "minecraft:air");
        cell.setString("role", "block");
        cell.setString("status", "preview");
        cell.setString("operation", "EDIT");
        cell.setBoolean("inScope", true);
        cell.setInteger("chosenChoice", -1);
        target(cell, stack);
        return cell;
    }

    static void target(NBTTagCompound cell, ItemStack stack) throws IllegalStateException {
        if (stack == null || stack.getItem() == null)
            throw new IllegalStateException("The native tutorial device is unavailable");
        String registry = String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()));
        cell.setTag("targetStack", stack.writeToNBT(new NBTTagCompound()));
        cell.setString(
            "wantId",
            String.valueOf(
                net.minecraft.block.Block.blockRegistry
                    .getNameForObject(net.minecraft.block.Block.getBlockFromItem(stack.getItem()))));
        cell.setInteger(
            "wantMeta",
            stack.getItem()
                .getMetadata(stack.getItemDamage()));
        cell.setString("exactIdentity", registry + "@" + stack.getItemDamage());
        cell.setBoolean("explicitTarget", true);
        cell.setBoolean("hasTargetOverride", true);
        int hint = "hatch".equals(cell.getString("role")) ? cell.getInteger("hintIndex") : -1;
        NBTTagCompound display = HologramObservedItemBindings
            .ebfTargetDisplay(stack, cell.getInteger("targetFacing"), hint);
        cell.setTag("targetDisplay", display);
        int chosen = -1;
        NBTTagList candidates = cell.getTagList("candidates", 10);
        for (int i = 0; i < candidates.tagCount(); i++) {
            ItemStack candidate = ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(i));
            if (candidate != null && stack.isItemEqual(candidate)
                && ItemStack.areItemStackTagsEqual(stack, candidate)) {
                chosen = i;
                break;
            }
        }
        cell.setInteger("chosenChoice", chosen);
    }

    /** Same exact-identity row converter as the server quote; isolated stock is explicitly empty. */
    static void updateMaterials(NBTTagCompound data) {
        NBTTagList demands = new NBTTagList(), cells = data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (cell.getBoolean("excluded") || !cell.hasKey("targetStack", 10)) continue;
            if (cell.getString("structureId")
                .startsWith("gtmb_text_")
                || cell.getString("structureId")
                    .startsWith("gtmb_picture_")) {
                try {
                    for (ItemStack material : com.miaokatze.gtmb.hologram.HologramNativePlacement.materials(
                        net.minecraft.block.Block.getBlockFromName(cell.getString("wantId")),
                        cell.getInteger("wantMeta"),
                        cell.getCompoundTag("targetDisplay")
                            .getCompoundTag("nativeTile")))
                        addDemand(demands, material, material.stackSize);
                } catch (IOException invalid) {
                    throw new IllegalStateException("Cannot quote the native attachment materials", invalid);
                }
            } else addDemand(demands, ItemStack.loadItemStackFromNBT(cell.getCompoundTag("targetStack")), 1);
        }
        NBTTagCompound quote = HologramMaterialQuote.fromRows(demands, new NBTTagList());
        quote.setString("session", data.getString("session"));
        quote.setLong("planRevision", data.getLong("planRevision"));
        quote.setLong("placementReviewNonce", data.getLong("placementReviewNonce"));
        quote.setString("validation", "Isolated tutorial plan; empty teaching stock; no player inventory observation");
        data.setTag("materialQuote", quote);
    }

    private static void addDemand(NBTTagList demands, ItemStack stack, int amount) {
        if (stack == null) throw new IllegalStateException("Cannot quote an unresolved tutorial item");
        NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
        row.setLong("required", amount);
        row.setLong("available", 0);
        demands.appendTag(row);
    }

    static void placeAttachment(NBTTagCompound data, NBTTagCompound request) throws IOException {
        if (!"blueprint".equals(data.getString("editorWorkspace"))) throw new IOException("投影模式不支持文字图片");
        com.miaokatze.gtmb.hologram.HologramTextBox definition = com.miaokatze.gtmb.hologram.HologramTextBox
            .read(request.getCompoundTag("textBox"));
        NBTTagList before = data.getTagList("cells", 10), after = new NBTTagList();
        java.util.Set<String> occupied = new java.util.HashSet<>();
        for (int i = 0; i < before.tagCount(); i++) {
            NBTTagCompound cell = before.getCompoundTagAt(i);
            if (definition.id.equals(cell.getString("structureId"))) continue;
            after.appendTag(cell.copy());
            occupied.add(cell.getInteger("dx") + "," + cell.getInteger("dy") + "," + cell.getInteger("dz"));
        }
        net.minecraft.block.Block host = net.minecraft.block.Block.getBlockFromName("littletiles:BlockLittleTiles");
        if (host == null) throw new IOException("LittleTiles host is unavailable");
        for (com.miaokatze.gtmb.hologram.HologramTextBox.Cell generated : definition.generate()) {
            if (occupied.contains(generated.x + "," + generated.y + "," + generated.z))
                throw new IOException("附加对象与教学规划冲突");
            NBTTagCompound payload = generated.tile();
            ItemStack stack = com.miaokatze.gtmb.hologram.HologramNativePlacement.sample(host, 0, payload);
            NBTTagCompound cell = buildingCell(data, generated.x, generated.y, generated.z, stack);
            cell.setString("wantId", "littletiles:BlockLittleTiles");
            cell.setString("structureId", definition.id);
            NBTTagList membership = new NBTTagList();
            membership.appendTag(new NBTTagString(definition.id));
            cell.setTag("structureIds", membership);
            cell.getCompoundTag("targetDisplay")
                .setTag("nativeTile", payload);
            after.appendTag(cell);
        }
        NBTTagList structures = new NBTTagList(), old = data.getTagList("structures", 10);
        for (int i = 0; i < old.tagCount(); i++) if (!definition.id.equals(
            old.getCompoundTagAt(i)
                .getString("id")))
            structures.appendTag(
                old.getCompoundTagAt(i)
                    .copy());
        NBTTagCompound declaration = new NBTTagCompound();
        declaration.setString("id", definition.id);
        declaration.setString(
            "name",
            definition.picture == null ? "文字 · " + definition.text : "图片 · " + definition.picture.filename);
        declaration.setBoolean("enabled", true);
        declaration.setBoolean("declared", true);
        declaration.setTag("textBox", definition.write());
        structures.appendTag(declaration);
        data.setTag("structures", structures);
        data.setTag("cells", after);
        data.setInteger("cellCount", after.tagCount());
        NBTTagCompound ack = new NBTTagCompound();
        ack.setLong("request", request.getLong("textRequest"));
        ack.setBoolean("accepted", true);
        ack.setString("id", definition.id);
        ack.setString("message", "已加入教学规划");
        data.setTag("textPlacementAck", ack);
    }

    static synchronized String primaryStructure(Kind kind) throws IOException {
        return sample(kind).primaryStructure;
    }

    static synchronized NBTTagCompound sameSourceMetadata(String id) throws IOException {
        Sample author = sample(Kind.AUTHOR), physical = sample(Kind.PHYSICAL), member = sample(Kind.MEMBER);
        require(
            author.primaryStructure.equals(id) && member.primaryStructure.equals(id),
            "No observed source configuration for this member");
        NBTTagCompound declared = null;
        NBTTagList structures = author.data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) if (id.equals(
            structures.getCompoundTagAt(i)
                .getString("id")))
            declared = structures.getCompoundTagAt(i);
        require(
            declared != null && !physical.data.getString("sourceClass")
                .isEmpty()
                && physical.data.getString("sourceClass")
                    .equals(declared.getString("sourceClass")),
            "Recorded source classes differ");
        for (String axis : new String[] { "x", "y", "z" }) require(
            physical.data.getInteger(axis) == declared.getInteger(axis),
            "Recorded controller positions differ");
        // Physical targetStack may still encode world metadata; actualStack identifies the controller device.
        NBTTagCompound before = controller(physical.data, true), after = controller(author.data, false);
        require(
            before != null && after != null
                && before.hasKey("actualStack", 10)
                && after.hasKey("actualStack", 10)
                && after.hasKey("targetStack", 10)
                && before.getCompoundTag("actualStack")
                    .equals(after.getCompoundTag("actualStack"))
                && before.getCompoundTag("actualStack")
                    .equals(after.getCompoundTag("targetStack")),
            "Recorded controller device identities differ");
        for (String axis : new String[] { "x", "y", "z" }) require(
            before.getInteger(axis) == after.getInteger(axis)
                && before.getInteger(axis) == physical.data.getInteger(axis),
            "Recorded controller cells differ");
        NBTTagCompound memberController = controller(member.data, false);
        require(
            memberController != null && after.getCompoundTag("targetStack")
                .equals(memberController.getCompoundTag("targetStack")),
            "Recorded member controller identity differs");
        for (String axis : new String[] { "x", "y", "z" }) require(
            after.getInteger(axis) == memberController.getInteger(axis),
            "Recorded member controller coordinates differ");
        require(
            required(manifest, "author.sourceClass").equals(required(manifest, "member.sourceClass")),
            "Recorded member source declaration differs");
        return HologramObservedItemBindings.restore(member.data);
    }

    static synchronized NBTTagCompound overviewMetadata() throws IOException {
        Sample author = sample(Kind.AUTHOR), overview = sample(Kind.OVERVIEW);
        require(
            author.primaryStructure.equals(overview.primaryStructure)
                && required(manifest, "author.sourceClass").equals(required(manifest, "overview.sourceClass")),
            "Recorded overview source declaration differs");
        NBTTagCompound before = controller(author.data, false), after = controller(overview.data, false);
        require(
            before != null && after != null
                && before.getCompoundTag("targetStack")
                    .equals(after.getCompoundTag("targetStack")),
            "Recorded overview controller identity differs");
        for (String axis : new String[] { "x", "y", "z" }) require(
            before.getInteger(axis) == after.getInteger(axis),
            "Recorded overview controller coordinates differ");
        return HologramObservedItemBindings.restore(overview.data);
    }

    private static NBTTagCompound controller(NBTTagCompound data, boolean physical) {
        NBTTagList cells = data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (physical ? cell.getBoolean("anchor") : "templateController".equals(cell.getString("role"))) return cell;
        }
        return null;
    }

    private static Sample sample(Kind kind) throws IOException {
        Sample existing = CACHE.get(kind);
        if (existing != null) return existing;
        if (manifest == null) {
            Properties loaded = properties(read("manifest.properties", 64 * 1024));
            require(
                "2".equals(loaded.getProperty("format")) && "gzip-nbt".equals(loaded.getProperty("encoding")),
                "Unsupported tutorial observation format");
            String pack = required(loaded, "gtnhVersion");
            if (!"notRecorded".equals(pack)) try {
                Class<?> source = Class.forName(
                    "com.dreammaster.coremod.DreamCoreMod",
                    false,
                    HologramEditorTutorialSamples.class.getClassLoader());
                require(
                    pack.equals(
                        source.getField("displayedModpackVersion")
                            .get(null)),
                    "Tutorial observations do not match this GTNH version");
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new IOException("Cannot verify the tutorial observation's GTNH version", failure);
            }
            manifest = loaded;
        }
        String prefix = kind.name()
            .toLowerCase(Locale.ROOT) + ".";
        String file = required(manifest, prefix + "snapshot");
        int bytes = number(manifest, prefix + "bytes");
        require(
            file.endsWith(".nbt.gz") && bytes > 0 && bytes <= 16 * 1024 * 1024,
            "Tutorial observation must be bounded compressed NBT");
        byte[] encoded = read(file, bytes);
        require(encoded.length == bytes, "Tutorial observation byte count changed");
        String digest = required(manifest, prefix + "sha256");
        require(digest.equals(sha256(encoded)), "Tutorial observation digest changed");
        byte[] evidence = read(required(manifest, prefix + "provenance"), 64 * 1024);
        require(
            required(manifest, prefix + "provenanceSha256").equals(sha256(evidence)),
            "Tutorial provenance digest changed");
        Properties provenance = properties(evidence);
        require(
            "true".equals(provenance.getProperty("fullData")) && "false".equals(provenance.getProperty("metadataOnly")),
            "Tutorial observation has no complete cells");
        require(
            digest.equals(provenance.getProperty("binarySha256"))
                && file.equals(provenance.getProperty("binarySnapshot")),
            "Tutorial snapshot provenance differs");
        require(
            "Minecraft CompressedStreamTools.writeCompressed".equals(provenance.getProperty("binaryFormat"))
                && "true".equals(provenance.getProperty("binaryFullByteArrayFidelity"))
                && "true".equals(provenance.getProperty("binaryRoundTripEqualsFullCopy"))
                && "true".equals(manifest.getProperty(prefix + "binaryFullByteArrayFidelity"))
                && "true".equals(manifest.getProperty(prefix + "binaryRoundTripEqualsFullCopy")),
            "Tutorial observation has no lossless binary export proof");
        require(
            required(manifest, prefix + "productSha256").equals(provenance.getProperty("productSha256")),
            "Tutorial producer jar provenance differs");
        for (String binding : new String[] { "coreSha256", "addonSha256" }) require(
            required(manifest, prefix + binding).equals(provenance.getProperty(binding)),
            "Tutorial runtime provenance differs: " + binding);
        require(bytes == number(provenance, "binaryBytes"), "Tutorial provenance byte count differs");
        int expandedBytes = number(manifest, prefix + "uncompressedBytes");
        require(
            expandedBytes > 0 && expandedBytes <= 16 * 1024 * 1024
                && expandedBytes == number(provenance, "binaryUncompressedBytes"),
            "Tutorial expanded payload provenance differs");
        NBTTagCompound data;
        try {
            data = CompressedStreamTools.readCompressed(new ByteArrayInputStream(encoded));
        } catch (Exception failure) {
            throw new IOException("Cannot decode the recorded tutorial observation", failure);
        }
        int cells = number(manifest, prefix + "cells");
        require(
            cells == number(provenance, "cells") && cells == number(provenance, "exportedCells"),
            "Tutorial provenance cell counts differ");
        require(
            data.hasKey("cells", 9) && data.getTagList("cells", 10)
                .tagCount() == cells && cells > 0,
            "Tutorial observation has incomplete geometry");
        require(data.getBoolean("virtual") == (kind != Kind.PHYSICAL), "Tutorial source context differs");
        String sourceClass = required(manifest, prefix + "sourceClass");
        String recordedClass = data.getString("sourceClass");
        if (kind != Kind.PHYSICAL) {
            NBTTagList sources = data.getTagList("structures", 10);
            for (int i = 0; i < sources.tagCount(); i++) if (manifest.getProperty(prefix + "primaryStructure", "")
                .equals(
                    sources.getCompoundTagAt(i)
                        .getString("id")))
                recordedClass = sources.getCompoundTagAt(i)
                    .getString("sourceClass");
        }
        require(sourceClass.equals(recordedClass), "Tutorial source declaration differs");
        try {
            Class.forName(sourceClass, false, HologramEditorTutorialSamples.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError missing) {
            throw new IOException("Tutorial machine class is unavailable", missing);
        }
        String primary = manifest.getProperty(prefix + "primaryStructure", "");
        validateStructureLinks(data, primary, number(manifest, prefix + "primaryCells"));
        if (kind == Kind.MEMBER) require(
            !primary.isEmpty() && primary.equals(data.getString("activeStructure")),
            "Tutorial member navigation was not accepted");
        if (kind == Kind.OVERVIEW) require(
            data.getString("activeStructure")
                .isEmpty(),
            "Tutorial overview navigation was not accepted");
        int candidateCells = 0;
        NBTTagList rows = data.getTagList("cells", 10);
        if (kind == Kind.MEMBER) {
            Set<Integer> selected = new HashSet<>();
            for (int index : data.getIntArray("selectedIndices")) require(
                index >= 0 && index < rows.tagCount() && selected.add(index),
                "Recorded member selection is invalid");
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound cell = rows.getCompoundTagAt(i);
                boolean belongs = primary.equals(cell.getString("structureId"));
                NBTTagList links = cell.getTagList("structureIds", 8);
                for (int j = 0; j < links.tagCount(); j++) belongs |= primary.equals(links.getStringTagAt(j));
                require(
                    selected.contains(i) == belongs,
                    "Recorded member selection does not match its exact footprint");
            }
        }
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getTagList("candidates", 10)
            .tagCount() > 0) candidateCells++;
        require(candidateCells == number(manifest, prefix + "candidateCells"), "Tutorial selected-cell details differ");
        if (kind == Kind.AUTHOR) require(candidateCells > 0, "Tutorial source has no recorded candidate details");
        require(
            data.getTagList("capabilities", 10)
                .tagCount() == number(manifest, prefix + "capabilities"),
            "Tutorial capability declarations differ");
        require(
            data.getCompoundTag("processing")
                .getInteger("requiredStages") == number(manifest, prefix + "requiredStages"),
            "Tutorial recorded processing stages differ");
        // Keep the original capture and provenance immutable. Only the derived portable observation enters UI code.
        String portableFile = file.replace(".nbt.gz", ".portable.nbt.gz");
        Properties bindings = properties(read("portable.properties", 16 * 1024));
        byte[] portable = read(portableFile, number(bindings, portableFile + ".bytes"));
        require(
            required(bindings, portableFile + ".sha256").equals(sha256(portable)),
            "Portable observation digest changed");
        NBTTagCompound rebound = CompressedStreamTools.readCompressed(new ByteArrayInputStream(portable));
        Sample loaded = new Sample(rebound, primary);
        CACHE.put(kind, loaded);
        return loaded;
    }

    private static void validateStructureLinks(NBTTagCompound data, String primary, int primaryCells)
        throws IOException {
        Set<String> declared = new HashSet<>();
        NBTTagList structures = data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            String id = structures.getCompoundTagAt(i)
                .getString("id");
            require(!id.isEmpty() && declared.add(id), "Tutorial structure identities are missing or duplicated");
        }
        int members = 0;
        NBTTagList cells = data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            String id = cell.getString("structureId");
            NBTTagList memberships = cell.getTagList("structureIds", 8);
            boolean primaryMember = !primary.isEmpty() && primary.equals(id);
            require(id.isEmpty() || declared.contains(id), "Tutorial cell references an unknown primary source");
            for (int j = 0; j < memberships.tagCount(); j++) {
                String member = memberships.getStringTagAt(j);
                require(declared.contains(member), "Tutorial cell references an unknown shared source");
                if (primary.equals(member)) primaryMember = true;
            }
            if (primaryMember) members++;
        }
        require(
            members == primaryCells && (primary.isEmpty() || declared.contains(primary) && members > 0),
            "Tutorial source membership does not match its recorded footprint");
    }

    private static byte[] read(String file, int limit) throws IOException {
        require(
            file.matches("[A-Za-z0-9_.-]+") && !file.contains("..") && limit > 0,
            "Invalid tutorial resource descriptor");
        try (InputStream input = HologramEditorTutorialSamples.class.getResourceAsStream(ROOT + file)) {
            if (input == null) throw new IOException("The recorded tutorial sample is unavailable: " + file);
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                require((long) output.size() + count <= limit, "Tutorial resource exceeded its recorded size");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static Properties properties(byte[] data) throws IOException {
        Properties result = new Properties();
        result.load(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        return result;
    }

    private static String required(Properties source, String key) throws IOException {
        String value = source.getProperty(key, "");
        require(!value.isEmpty(), "Missing tutorial provenance field: " + key);
        return value;
    }

    private static int number(Properties source, String key) throws IOException {
        try {
            return Integer.parseInt(required(source, key));
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid tutorial provenance count: " + key, invalid);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            StringBuilder text = new StringBuilder();
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static final class Sample {

        final NBTTagCompound data;
        final String primaryStructure;

        Sample(NBTTagCompound data, String primaryStructure) {
            this.data = data;
            this.primaryStructure = primaryStructure;
        }
    }
}
