package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.AEApi;
import appeng.api.definitions.IItemDefinition;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.helpers.IInterfaceHost;
import appeng.tile.networking.TileCableBus;
import appeng.util.Platform;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

/** FML-only precision fixtures serialized by real AE part and AE stack APIs; never install or tick a tile. */
public final class HologramAeNativeResourcesSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-AE-RESOURCES] ";

    private HologramAeNativeResourcesSmoke() {}

    public static void run() throws Exception {
        IPart eu = part(
            AEApi.instance()
                .definitions()
                .parts()
                .p2PTunnelEU(),
            "appeng.parts.p2p.PartP2PIC2Power");
        NBTTagCompound initial = new NBTTagCompound();
        initial.setBoolean("output", true);
        initial.setDouble("OutputVoltageA", 512.0D);
        initial.setDouble("OutputVoltageB", 2048.0D);
        eu.readFromNBT(initial);
        NBTTagCompound zero = cableBus(eu);
        accepted(zero, "empty_eu_packets_with_voltage_configuration");
        require(extra(zero).getDouble("OutputVoltageA") == 512.0D, "official_voltage_writer");
        for (String packet : new String[] { "OutputPacket", "OutputPacket2" }) {
            for (double value : new double[] { 1.0D, 0.25D, -1.0D, Double.NaN, Double.POSITIVE_INFINITY }) {
                NBTTagCompound seed = (NBTTagCompound) initial.copy();
                seed.setDouble(packet, value);
                eu.readFromNBT(seed);
                NBTTagCompound buffered = cableBus(eu);
                require(extra(buffered).hasKey(packet, 6), "official_double_packet_writer");
                rejected(buffered, packet + "_" + value);
            }
            NBTTagCompound malformed = (NBTTagCompound) zero.copy();
            extra(malformed).setLong(packet, 0L);
            rejected(malformed, packet + "_wrong_type_even_zero");
        }
        NBTTagCompound retainedBuffer = (NBTTagCompound) zero.copy();
        extra(retainedBuffer).setDouble("OutputPacket", 0.5D);
        String bufferedCapture = HologramBlueprintNbt.encodeCaptured(retainedBuffer);
        NBTTagCompound selectedDefinition = HologramBlueprintNbt
            .decode(HologramBlueprintNbt.filterCaptured(bufferedCapture, Collections.singletonList("def:2"), false));
        accepted(selectedDefinition, "deselected_buffer_not_restored");
        require(
            bufferedCapture.equals(HologramBlueprintNbt.encodeCaptured(retainedBuffer))
                && extra(retainedBuffer).getDouble("OutputPacket") == 0.5D,
            "selection_preserves_captured_buffer");

        IItemDefinition[] interfaces = { AEApi.instance()
            .definitions()
            .parts()
            .iface(),
            AEApi.instance()
                .definitions()
                .parts()
                .p2PTunnelMEInterface() };
        String[] types = { "appeng.parts.misc.PartInterface", "appeng.parts.p2p.PartP2PInterface" };
        for (int type = 0; type < interfaces.length; type++) {
            IPart iface = part(interfaces[type], types[type]);
            // The official reader may notify its host. A detached host has no world or network to notify.
            TileCableBus host = new TileCableBus();
            iface.setPartHostInfo(ForgeDirection.NORTH, host, host);
            for (boolean fluid : new boolean[] { false, true }) {
                for (long quantity : new long[] { 0L, 1L, Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1L,
                    Long.MAX_VALUE, -1L }) {
                    NBTTagCompound queued = queued(iface, fluid, quantity);
                    NBTTagCompound row = extra(queued).getTagList("waitingToSend", 10)
                        .getCompoundTagAt(0);
                    require(
                        row.hasKey("Count", 1) && row.getByte("Count") == 0
                            && row.hasKey("Cnt", 4)
                            && row.getLong("Cnt") == quantity,
                        "official_queue_count_writer_" + type + "_" + fluid);
                    String label = "interface_" + type + "_" + fluid + "_" + quantity;
                    if (quantity >= 0 && quantity <= Integer.MAX_VALUE) accepted(queued, label);
                    else rejected(queued, label);
                }
                NBTTagCompound valid = queued(iface, fluid, 1L);
                for (boolean missing : new boolean[] { false, true }) {
                    NBTTagCompound malformed = (NBTTagCompound) valid.copy();
                    NBTTagCompound row = extra(malformed).getTagList("waitingToSend", 10)
                        .getCompoundTagAt(0);
                    if (missing) row.removeTag("Cnt");
                    else row.setInteger("Cnt", 1);
                    rejected(malformed, "queue_count_" + (missing ? "missing" : "wrong_type"));
                }
                NBTTagCompound malformed = (NBTTagCompound) valid.copy();
                extra(malformed).setString("waitingToSend", "invalid");
                rejected(malformed, "queue_wrong_type");
                NBTTagList strings = new NBTTagList();
                strings.appendTag(new NBTTagString("invalid"));
                extra(malformed).setTag("waitingToSend", strings);
                rejected(malformed, "queue_wrong_entry_type");
            }
            NBTTagCompound configuration = queued(iface, false, 1L);
            NBTTagCompound config = new NBTTagCompound();
            config.setLong("Cnt", Long.MAX_VALUE);
            extra(configuration).setTag("privateConfiguration", config);
            accepted(configuration, "ordinary_cnt_configuration_preserved_" + type);
        }

        IPart ordinary = part(
            AEApi.instance()
                .definitions()
                .parts()
                .p2PTunnelME(),
            "appeng.parts.p2p.PartP2PTunnelME");
        NBTTagCompound unrelated = cableBus(ordinary);
        extra(unrelated).setDouble("OutputPacket", 123.0D);
        extra(unrelated).setString("waitingToSend", "ordinary configuration");
        accepted(unrelated, "exact_official_part_identity_required");
        System.out.println(PREFIX + "complete");
    }

    private static IPart part(IItemDefinition definition, String type) {
        require(
            definition.maybeStack(1)
                .isPresent(),
            "required_part_enabled_" + type);
        ItemStack stack = definition.maybeStack(1)
            .get();
        require(stack.getItem() instanceof IPartItem, "official_part_item_" + type);
        IPart part = ((IPartItem) stack.getItem()).createPartFromItemStack(stack);
        require(
            part != null && part.getClass()
                .getName()
                .equals(type),
            "official_part_class_" + type);
        return part;
    }

    private static NBTTagCompound queued(IPart part, boolean fluid, long quantity) {
        IAEStack<?> stack = fluid ? AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 1))
            : AEItemStack.create(new ItemStack(Items.iron_ingot));
        require(stack != null, "official_ae_stack");
        stack.setStackSize(quantity);
        NBTTagList waiting = new NBTTagList();
        waiting.appendTag(Platform.writeStackNBT(stack, new NBTTagCompound(), true));
        NBTTagCompound serialized = new NBTTagCompound();
        serialized.setTag("waitingToSend", waiting);
        ((IInterfaceHost) part).getInterfaceDuality()
            .readFromNBT(serialized);
        return cableBus(part);
    }

    private static NBTTagCompound cableBus(IPart part) {
        NBTTagCompound payload = new NBTTagCompound();
        NBTTagCompound definition = new NBTTagCompound();
        part.getItemStack(PartItemStack.World)
            .writeToNBT(definition);
        NBTTagCompound extra = new NBTTagCompound();
        part.writeToNBT(extra);
        payload.setTag("def:2", definition);
        payload.setTag("extra:2", extra);
        return payload;
    }

    private static NBTTagCompound extra(NBTTagCompound payload) {
        return payload.getCompoundTag("extra:2");
    }

    private static void accepted(NBTTagCompound payload, String label) throws IOException {
        verify(payload, false, label);
        verify(multipart(payload), false, label + "_fmp");
    }

    private static void rejected(NBTTagCompound payload, String label) throws IOException {
        verify(payload, true, label);
        verify(multipart(payload), true, label + "_fmp");
    }

    private static NBTTagCompound multipart(NBTTagCompound cable) {
        NBTTagCompound payload = new NBTTagCompound();
        NBTTagCompound part = (NBTTagCompound) cable.copy();
        part.setString("id", "ae2_cablebus");
        NBTTagList parts = new NBTTagList();
        parts.appendTag(part);
        payload.setTag("parts", parts);
        return payload;
    }

    private static void verify(NBTTagCompound payload, boolean reject, String label) throws IOException {
        // NBTTagDouble.equals compares with ==, so even a faithful NaN copy is unequal.
        // Compare the complete native serialization, including types and values, instead.
        byte[] before = serialized(payload);
        String saved = HologramBlueprintNbt.encodeCaptured(payload);
        boolean failed = false;
        try {
            HologramAeNativeResources.validateSelectedFinalPayload(payload);
        } catch (IOException expected) {
            require(
                expected.getMessage()
                    .contains("AE2"),
                label + "_explicit_ae_error");
            failed = true;
        }
        require(failed == reject, label + "_result");
        require(
            Arrays.equals(before, serialized(payload)) && saved.equals(HologramBlueprintNbt.encodeCaptured(payload)),
            label + "_capture_unchanged");
        failed = false;
        try {
            HologramBlueprintResources.plan(HologramBlueprintNbt.decode(saved));
        } catch (IOException expected) {
            require(
                expected.getMessage()
                    .contains("AE2"),
                label + "_explicit_selected_plan_ae_error");
            failed = true;
        }
        require(failed == reject, label + "_selected_plan_result");
        require(
            Arrays.equals(before, serialized(payload)) && saved.equals(HologramBlueprintNbt.encodeCaptured(payload)),
            label + "_source_after_plan_unchanged");
        System.out.println(PREFIX + "PASS " + label);
    }

    private static byte[] serialized(NBTTagCompound payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(payload, output);
        }
        return bytes.toByteArray();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException(PREFIX + label);
    }
}
