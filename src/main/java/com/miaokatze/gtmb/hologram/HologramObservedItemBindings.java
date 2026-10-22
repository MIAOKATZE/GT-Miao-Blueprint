package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Narrow bridge to the blueprint codec's existing registry-name normalization. */
public final class HologramObservedItemBindings {

    private static final java.util.Map<Integer, com.gtnewhorizon.structurelib.structure.IStructureElement<?>> EBF_ELEMENTS = new java.util.HashMap<>();

    private HologramObservedItemBindings() {}

    public static NBTTagCompound restore(NBTTagCompound portable) throws IOException {
        return HologramBlueprintNbt.restorePortableObservation(portable);
    }

    /** EBF shape groups from GT's native definition: bottom b, top f and top-centre m. No inventory/world query. */
    public static void ebfCandidates(NBTTagCompound cell) {
        int hint = cell.getInteger("hintIndex");
        IHatchElement<?>[] roles = hint == 1 ? new IHatchElement<?>[] { HatchElement.Muffler }
            : hint == 2 ? new IHatchElement<?>[] { HatchElement.OutputHatch }
                : new IHatchElement<?>[] { HatchElement.InputHatch, HatchElement.OutputHatch, HatchElement.InputBus,
                    HatchElement.OutputBus, HatchElement.Maintenance, HatchElement.Energy };
        com.gtnewhorizon.structurelib.structure.IStructureElement<?> source = ebfElement(hint);
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (com.gtnewhorizon.structurelib.structure.IStructureElement<?> part : HologramElementCatalog.parts(source))
            for (IHatchElement<?> role : HologramHatchPolicy.declared(part)) declared.add(role.name());
        for (IHatchElement<?> role : roles) if (!declared.contains(role.name()))
            throw new IllegalStateException("The current native EBF does not declare " + role.name());
        NBTTagList candidates = new NBTTagList(), names = new NBTTagList();
        java.util.List<Integer> tiers = new java.util.ArrayList<>();
        for (IMetaTileEntity prototype : GregTechAPI.METATILEENTITIES) {
            if (prototype == null) continue;
            for (IHatchElement<?> role : roles) if (HologramHatchPolicy.matchesHatch(role, prototype)) {
                ItemStack stack;
                try {
                    stack = prototype.getStackForm(1);
                } catch (RuntimeException | LinkageError unavailablePrototype) {
                    continue;
                }
                if (stack == null) continue;
                candidates.appendTag(stack.writeToNBT(new NBTTagCompound()));
                names.appendTag(new NBTTagString(role.name()));
                tiers.add(HologramCellPolicy.hatchTier(stack));
                break;
            }
        }
        if (hint != 1) {
            ItemStack casing = new ItemStack(GregTechAPI.sBlockCasings1, 1, 11);
            candidates.appendTag(casing.writeToNBT(new NBTTagCompound()));
            names.appendTag(new NBTTagString("外壳/方块"));
            tiers.add(-1);
        }
        cell.setTag("candidates", candidates);
        cell.setTag("candidateRoles", names);
        cell.setIntArray(
            "candidateTiers",
            tiers.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        cell.setBoolean("candidateComplete", true);
        cell.setString("candidateBoundary", "GT EBF 原生 b/f/m 声明角色；同角色注册设备候选");
    }

    private static synchronized com.gtnewhorizon.structurelib.structure.IStructureElement<?> ebfElement(int hint) {
        com.gtnewhorizon.structurelib.structure.IStructureElement<?> cached = EBF_ELEMENTS.get(hint);
        if (cached != null) return cached;
        gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace source = new gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace(
            "gtmb_tutorial_declaration");
        for (com.gtnewhorizon.structurelib.structure.IStructureElement<?> element : source.getStructureDefinition()
            .getStructureFor("main"))
            for (com.gtnewhorizon.structurelib.structure.IStructureElement<?> part : HologramElementCatalog
                .parts(element)) {
                    java.util.List<IHatchElement<?>> roles = HologramHatchPolicy.declared(part);
                    boolean matches = hint == 1 ? roles.size() == 1 && roles.contains(HatchElement.Muffler)
                        : hint == 2 ? roles.size() == 1 && roles.contains(HatchElement.OutputHatch)
                            : roles.contains(HatchElement.InputBus) && roles.contains(HatchElement.Energy);
                    if (matches) {
                        EBF_ELEMENTS.put(hint, element);
                        return element;
                    }
                }
        throw new IllegalStateException("Cannot inspect the current native EBF hatch declaration");
    }

    public static NBTTagCompound ebfTargetDisplay(ItemStack stack, int facing, int hint) {
        if (!(stack.getItem() instanceof gregtech.common.blocks.ItemMachines)) {
            NBTTagCompound display = new NBTTagCompound();
            display.setInteger("color", -1);
            return display;
        }
        if (hint < 0) {
            NBTTagCompound payload = new NBTTagCompound();
            payload.setShort("mID", (short) stack.getItemDamage());
            payload.setShort("mFacing", (short) (facing < 0 ? 2 : facing));
            NBTTagCompound display = HologramDisplaySnapshot.targetPayload(null, stack, facing, null, payload, true);
            display.setTag("nativeTile", payload);
            return display;
        }
        com.gtnewhorizon.structurelib.structure.IStructureElement<?> element = ebfElement(hint);
        try {
            String payload = HologramDisplaySnapshot.retainCasing(stack, element, "");
            NBTTagCompound display = HologramDisplaySnapshot.target(stack, facing, element, payload, true);
            NBTTagCompound nativeTile = HologramBlueprintNbt.decode(payload);
            if (nativeTile != null) display.setTag("nativeTile", nativeTile);
            if (display.hasKey("nativeTile", 10) && facing >= 0) display.getCompoundTag("nativeTile")
                .setShort("mFacing", (short) facing);
            return display;
        } catch (IOException invalid) {
            throw new IllegalStateException("Cannot prepare the native EBF hatch display", invalid);
        }
    }

    public static ItemStack chooseObservedHatch(NBTTagCompound cell, String role, int requested, boolean downgrade) {
        NBTTagList candidates = cell.getTagList("candidates", 10), roles = cell.getTagList("candidateRoles", 8);
        java.util.List<Integer> indices = new java.util.ArrayList<>(), tiers = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.tagCount() && i < roles.tagCount(); i++)
            if (role.equals(roles.getStringTagAt(i))) {
                ItemStack stack = ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(i));
                int tier = HologramCellPolicy.hatchTier(stack);
                if (tier == Integer.MAX_VALUE) continue;
                indices.add(i);
                tiers.add(tier);
            }
        int[] values = tiers.stream()
            .mapToInt(Integer::intValue)
            .toArray();
        int chosen = HologramHatchPreset.choose(values, new int[values.length], requested, downgrade);
        return chosen < 0 ? null : ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(indices.get(chosen)));
    }
}
