// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Test-only CLIENT-thread creative enumeration. The caller writes the detached result to its managed local path. */
public final class TestLabNativeCatalogExport {

    private final Set<String> mods = new TreeSet<>();
    private final TestLabNativeCatalog catalog;
    private boolean complete;

    public TestLabNativeCatalogExport(String[] registryMods) throws IOException {
        if (!TestLab.clientMappingAvailable() || !FMLCommonHandler.instance()
            .getSide()
            .isClient()) throw new IOException("Client catalog export requires the local enabled testlab client");
        Collections.addAll(mods, registryMods);
        if (mods.isEmpty()) throw new IOException("Select registry mod IDs to export");
        for (String mod : mods) if (!mod.matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid registry mod ID");
        catalog = new TestLabNativeCatalog(mods);
    }

    /** Call on successive real client ticks. Native callbacks are executed by their registered item implementations. */
    public boolean step() throws Exception {
        complete = catalog.step();
        return complete;
    }

    public NBTTagCompound snapshot() throws IOException {
        if (!complete) throw new IOException("Native client catalog enumeration is incomplete");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        NBTTagList declared = new NBTTagList();
        for (String mod : mods) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", mod);
            row.setString("version", TestLabNativeCatalog.version(mod));
            declared.appendTag(row);
        }
        tag.setTag("mods", declared);
        NBTTagList versions = new NBTTagList();
        for (ModContainer mod : Loader.instance()
            .getModList()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", mod.getModId());
            row.setString("version", mod.getVersion());
            versions.appendTag(row);
        }
        tag.setTag("versions", versions);
        NBTTagList stacks = new NBTTagList();
        for (TestLabNativeCatalog.Page page : catalog.pages) for (ItemStack stack : page.stacks) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("registry", Item.itemRegistry.getNameForObject(stack.getItem()));
            row.setInteger("damage", stack.getItemDamage());
            row.setTag("stack", TestLabNativeCatalog.stack(stack));
            stacks.appendTag(row);
        }
        tag.setTag("stacks", stacks);
        tag.setTag("issues", catalog.issues.copy());
        tag.setBoolean("enumerationComplete", !catalog.enumerationFailed);
        return tag;
    }
}
