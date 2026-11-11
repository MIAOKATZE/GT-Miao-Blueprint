// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.miaokatzecore.api.items.NativeItemIds;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Actual registered stacks, including every variant returned by each item's native creative tabs. */
final class TestLabNativeCatalog {

    static final int PAGE_SIZE = 128, MAX_PAGES = 4096;
    final List<Page> pages = new ArrayList<>();
    final NBTTagList issues = new NBTTagList();
    private final Set<String> mods;
    private final Iterator<?> items = Item.itemRegistry.iterator();
    private final Iterator<?> blocks = Block.blockRegistry.iterator();
    private final Map<String, Map<String, ItemStack>> collected = new TreeMap<>();
    private boolean itemDone, done;
    private NBTTagList imported;
    private int importCursor;
    boolean enumerationFailed;

    TestLabNativeCatalog(Set<String> mods) {
        this.mods = mods;
    }

    TestLabNativeCatalog(Set<String> mods, NBTTagCompound supplied) throws IOException {
        this(mods);
        if (supplied.getInteger("schema") != 1) throw new IOException("Native catalog input schema must be 1");
        NBTTagList declared = supplied.getTagList("mods", 10);
        Set<String> found = new java.util.TreeSet<>();
        for (int i = 0; i < declared.tagCount(); i++) {
            NBTTagCompound row = declared.getCompoundTagAt(i);
            String id = row.getString("id");
            if (!found.add(id) || !version(id).equals(row.getString("version")))
                throw new IOException("Client/server native catalog mod version differs: " + id);
        }
        if (!mods.equals(found))
            throw new IOException("Native catalog input selected mods differ from local configuration");
        if (!supplied.hasKey("versions", 9)) throw new IOException("Native catalog input mod versions are missing");
        NBTTagList versions = supplied.getTagList("versions", 10);
        for (int i = 0; i < versions.tagCount(); i++) {
            NBTTagCompound row = versions.getCompoundTagAt(i);
            ModContainer installed = Loader.instance()
                .getIndexedModList()
                .get(row.getString("id"));
            if (installed != null && !installed.getVersion()
                .equals(row.getString("version")))
                throw new IOException("Client/server installed mod version differs: " + row.getString("id"));
        }
        if (!supplied.getBoolean("enumerationComplete"))
            throw new IOException("Client native catalog explicitly reports incomplete enumeration");
        imported = supplied.getTagList("stacks", 10);
        NBTTagList inputIssues = supplied.getTagList("issues", 10);
        for (int i = 0; i < inputIssues.tagCount(); i++) {
            NBTTagCompound row = inputIssues.getCompoundTagAt(i);
            issues.appendTag(row.copy());
            if ("creative-enumeration-error".equals(row.getString("kind"))) enumerationFailed = true;
        }
    }

    boolean step() throws Exception {
        if (done) return true;
        if (imported != null && importCursor < imported.tagCount()) {
            NBTTagCompound row = imported.getCompoundTagAt(importCursor++);
            String registry = row.getString("registry");
            Item item = (Item) Item.itemRegistry.getObject(registry);
            if (item == null || !registry.equals(Item.itemRegistry.getNameForObject(item))
                || !mods.contains(mod(registry)))
                throw new IOException("Unregistered/unselected native imported item: " + registry);
            NBTTagCompound raw = (NBTTagCompound) row.getCompoundTag("stack")
                .copy();
            NativeItemIds.write(raw, Item.getIdFromItem(item));
            ItemStack stack = ItemStack.loadItemStackFromNBT(raw);
            if (stack == null || stack.getItem() != item || stack.stackSize != 1)
                throw new IOException("Invalid native imported stack: " + registry);
            if (!row.hasKey("damage", 3) || row.getInteger("damage") != stack.getItemDamage())
                throw new IOException("Native imported variant metadata differs: " + registry);
            collect(registry, stack);
            return false;
        }
        if (imported != null) itemDone = true;
        if (!itemDone && items.hasNext()) {
            Item item = (Item) items.next();
            String registry = Item.itemRegistry.getNameForObject(item);
            if (!mods.contains(mod(registry))) return false;
            List<ItemStack> variants = new ArrayList<>();
            try {
                CreativeTabs[] tabs = item.getCreativeTabs();
                if (tabs == null || tabs.length == 0) item.getSubItems(item, null, variants);
                else for (CreativeTabs tab : tabs) item.getSubItems(item, tab, variants);
            } catch (RuntimeException | LinkageError failure) {
                enumerationFailed = true;
                issue(registry, "creative-enumeration-error", failure.toString());
            }
            if (variants.isEmpty()) {
                if (item.getHasSubtypes()) issue(registry, "no-native-subitems", "No invented metadata variants");
                else variants.add(new ItemStack(item, 1, 0));
            }
            for (ItemStack variant : variants) {
                if (variant == null || variant.getItem() == null) {
                    issue(registry, "invalid-native-subitem", "Native creative callback returned an empty stack");
                    continue;
                }
                String actual = Item.itemRegistry.getNameForObject(variant.getItem());
                if (actual == null) {
                    issue(
                        registry,
                        "unregistered-native-subitem",
                        "Native creative callback returned an unregistered item");
                    continue;
                }
                String mod = mod(actual);
                if (!mods.contains(mod)) continue;
                ItemStack copy = variant.copy();
                copy.stackSize = 1;
                collect(actual, copy);
            }
            return false;
        }
        itemDone = true;
        if (blocks.hasNext()) {
            Block block = (Block) blocks.next();
            String registry = Block.blockRegistry.getNameForObject(block);
            if (mods.contains(mod(registry)) && Item.getItemFromBlock(block) == null)
                issue(registry, "registered-block-without-item", "No independent native ItemStack placement path");
            return false;
        }
        for (Map.Entry<String, Map<String, ItemStack>> group : collected.entrySet()) {
            List<ItemStack> stacks = new ArrayList<>(
                group.getValue()
                    .values());
            for (int offset = 0, number = 0; offset < stacks.size(); offset += PAGE_SIZE, number++) {
                if (pages.size() == MAX_PAGES) throw new IOException("Native platform catalog exceeds 4096 pages");
                pages.add(
                    new Page(
                        group.getKey(),
                        number,
                        pages.size(),
                        stacks.subList(offset, Math.min(offset + PAGE_SIZE, stacks.size()))));
            }
        }
        for (String mod : mods) if (!collected.containsKey(mod))
            issue(mod, "no-registered-items", "Configured mod has no enumerated registered stacks");
        done = true;
        collected.clear();
        return true;
    }

    private void collect(String registry, ItemStack stack) {
        String mod = mod(registry);
        Map<String, ItemStack> group = collected.get(mod);
        if (group == null) collected.put(mod, group = new TreeMap<>());
        group.put(registry + ":" + stack(stack), stack);
    }

    static String version(String mod) {
        if ("minecraft".equals(mod)) return "1.7.10";
        ModContainer container = Loader.instance()
            .getIndexedModList()
            .get(mod);
        return container == null ? "no-loader-mod-with-this-registry-namespace" : container.getVersion();
    }

    private void issue(String registry, String kind, String detail) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("registry", registry == null ? "" : registry);
        row.setString("kind", kind);
        row.setString("detail", detail);
        issues.appendTag(row);
    }

    static String mod(String registry) {
        if (registry == null) return "";
        int colon = registry.indexOf(':');
        return colon < 0 ? "minecraft" : registry.substring(0, colon);
    }

    static NBTTagCompound stack(ItemStack stack) {
        NBTTagCompound tag = stack.writeToNBT(new NBTTagCompound());
        NativeItemIds.write(tag, Item.getIdFromItem(stack.getItem()));
        return tag;
    }

    static final class Page {

        final String id, mod, fingerprint;
        final int index, number;
        final List<ItemStack> stacks;

        Page(String mod, int number, int index, List<ItemStack> stacks) throws Exception {
            this.mod = mod;
            this.number = number;
            this.index = index;
            id = mod + "-" + number;
            List<ItemStack> copies = new ArrayList<>();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ItemStack stack : stacks) {
                copies.add(stack.copy());
                digest.update(
                    Item.itemRegistry.getNameForObject(stack.getItem())
                        .getBytes(StandardCharsets.UTF_8));
                digest.update(
                    TestLabNativeCatalog.stack(stack)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
            fingerprint = hash.toString();
            this.stacks = Collections.unmodifiableList(copies);
        }

        NBTTagCompound summary(TestLabConfig cfg) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", id);
            tag.setString("mod", mod);
            tag.setInteger("page", number);
            tag.setInteger("index", index);
            tag.setInteger("entries", stacks.size());
            tag.setString("fingerprint", fingerprint);
            tag.setTag(
                "source",
                cfg.nativeZone(index, false)
                    .tag());
            tag.setTag(
                "target",
                cfg.nativeZone(index, true)
                    .tag());
            return tag;
        }
    }
}
