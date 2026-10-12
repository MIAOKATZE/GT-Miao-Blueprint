package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;

/** Exact AE2 rv3-beta-1080 native schemas which cannot survive the current payment precision. */
final class HologramAeNativeResources {

    private HologramAeNativeResources() {}

    /** Validate only the selected final payload, before native readers/drop callbacks can clamp its quantities. */
    static void validateSelectedFinalPayload(NBTTagCompound payload) throws IOException {
        if (payload == null) return;
        inspectCableBus(payload, "");
        // ForgeMultipart saves the same CableBusContainer schema directly in this registered part.
        if (!payload.hasKey("parts", 9)) return;
        NBTTagList parts = payload.getTagList("parts", 10);
        for (int i = 0; i < parts.tagCount(); i++) {
            NBTTagCompound part = parts.getCompoundTagAt(i);
            if (part.hasKey("id", 8) && part.getString("id")
                .equals("ae2_cablebus")) inspectCableBus(part, "parts[" + i + "].");
        }
    }

    private static void inspectCableBus(NBTTagCompound payload, String path) throws IOException {
        // These are CableBusContainer's seven official ForgeDirection ordinals, including UNKNOWN.
        for (int side = 0; side < 7; side++) {
            String definitionKey = "def:" + side;
            String extraKey = "extra:" + side;
            if (!payload.hasKey(definitionKey, 10) || !payload.hasKey(extraKey, 10)) continue;
            NBTTagCompound extra = payload.getCompoundTag(extraKey);
            if (!extra.hasKey("OutputPacket") && !extra.hasKey("OutputPacket2") && !extra.hasKey("waitingToSend"))
                continue;
            IPart part = identify(payload.getCompoundTag(definitionKey), path + definitionKey);
            String type = part.getClass()
                .getName();
            if (type.equals("appeng.parts.p2p.PartP2PIC2Power")) {
                emptyPacket(extra, "OutputPacket", path + extraKey);
                emptyPacket(extra, "OutputPacket2", path + extraKey);
            } else if (type.equals("appeng.parts.misc.PartInterface")
                || type.equals("appeng.parts.p2p.PartP2PInterface")) {
                    exactWaitingQueue(extra, path + extraKey);
                }
        }
    }

    private static IPart identify(NBTTagCompound definition, String key) throws IOException {
        // nativePayload resolves stable item transport markers on an isolated copy; never alter saved/captured NBT.
        ItemStack stack = ItemStack.loadItemStackFromNBT(HologramBlueprintNbt.nativePayload(definition));
        if (stack == null || !(stack.getItem() instanceof IPartItem))
            throw unsupported(key, "AE2 resource-bearing part identity is unavailable");
        try {
            IPart part = ((IPartItem) stack.getItem()).createPartFromItemStack(stack);
            if (part == null) throw unsupported(key, "AE2 resource-bearing part identity is unavailable");
            return part;
        } catch (RuntimeException | LinkageError invalid) {
            throw new IOException("Unsupported AE2 resource-bearing part " + key, invalid);
        }
    }

    private static void emptyPacket(NBTTagCompound extra, String packet, String key) throws IOException {
        if (!extra.hasKey(packet)) return;
        // The integer EU budget cannot represent these double buffers. NaN/infinity also compare nonzero.
        if (!extra.hasKey(packet, 6) || extra.getDouble(packet) != 0.0D)
            throw unsupported(key + "." + packet, "AE2 IC2 P2P buffer must be empty before restoration");
    }

    private static void exactWaitingQueue(NBTTagCompound extra, String key) throws IOException {
        if (!extra.hasKey("waitingToSend")) return;
        key += ".waitingToSend";
        if (!extra.hasKey("waitingToSend", 9)) throw unsupported(key, "AE2 waiting queue must be a list");
        NBTTagList waiting = (NBTTagList) extra.getTag("waitingToSend");
        if (waiting.tagCount() > 0 && waiting.func_150303_d() != 10)
            throw unsupported(key, "AE2 waiting queue entries must be compounds");
        for (int i = 0; i < waiting.tagCount(); i++) {
            NBTTagCompound queued = waiting.getCompoundTagAt(i);
            // AEItemStack and AEFluidStack both serialize Count=0, with the actual quantity in this long.
            // Native getDrops/getItemStack/getFluidStack clamps it to Integer.MAX_VALUE.
            if (!queued.hasKey("Cnt", 4) || queued.getLong("Cnt") < 0 || queued.getLong("Cnt") > Integer.MAX_VALUE)
                throw unsupported(
                    key + "[" + i + "].Cnt",
                    "AE2 queued item/fluid quantity exceeds exact payment precision");
        }
    }

    private static IOException unsupported(String key, String detail) {
        return new IOException(
            "Unsupported retained resource " + key
                + ": "
                + detail
                + "; deselect this NBT root or empty the native buffer before capture");
    }
}
