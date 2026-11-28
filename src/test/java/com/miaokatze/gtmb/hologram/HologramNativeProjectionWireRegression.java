// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.HatchChoice;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Projection;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Native action intent is bounded data, never a client-supplied structure or an old machine profile. */
final class HologramNativeProjectionWireRegression {

    static void run() {
        for (Operation operation : Operation.values()) {
            var channels = new LinkedHashMap<String, Integer>();
            channels.put("coil", 3);
            channels.put("casing", 4);
            channels.put("glass", 2);
            var settings = new NBTTagCompound();
            settings.setBoolean("presetActive", true);
            settings.setInteger("hatchTier", 2);
            settings.setBoolean("hatchDowngrade", true);
            var target = new Projection(
                1000,
                operation,
                channels,
                Collections.singletonList(new HatchChoice(-1, 0, 1, 44, 2)),
                1,
                27,
                settings).withNativeTargetDigest(String.join("", Collections.nCopies(64, "a")));
            channels.clear();
            settings.setInteger("hatchTier", 9);
            SimpleAssert.that(
                target.channels()
                    .size() == 3
                    && target.configuration()
                        .getInteger("hatchTier") == 2,
                "confirmed target owns a copy of all channels and preset settings");
            var cold = Confirmation.projection(
                new Key(UUID.randomUUID(), operation.ordinal() + 1),
                7,
                UUID.randomUUID(),
                10,
                80,
                -20,
                2,
                0,
                0,
                1,
                true,
                false,
                21,
                target);
            var packet = HologramStockNativeWire.probe(cold);
            var decoded = HologramStockNativeWire.readRequest(packet);
            SimpleAssert.that(
                decoded != null && decoded.confirmation()
                    .projection()
                    .equals(target),
                "every action carries the complete named-channel/preset/hatch/recovery intent");
            var warm = cold;
            SimpleAssert.that(
                HologramStockNativeWire.readRequest(HologramStockNativeWire.confirm(warm))
                    .confirmation()
                    .equals(warm),
                "confirmed action survives the actual native codec");
            if (operation != Operation.DEMOLISH) {
                var observationOnly = Confirmation.projection(
                    cold.key(),
                    cold.dimension(),
                    cold.tool(),
                    cold.x(),
                    cold.y(),
                    cold.z(),
                    cold.side(),
                    cold.slot(),
                    cold.facing(),
                    cold.main(),
                    cold.mainExplicit(),
                    cold.noHatches(),
                    cold.sources(),
                    target.withNativeTargetDigest(""));
                SimpleAssert.that(
                    HologramStockNativeWire.readRequest(HologramStockNativeWire.probe(observationOnly)) != null,
                    "an observation can precede publication of a complete target");
                try {
                    HologramStockNativeWire.confirm(observationOnly);
                    throw new AssertionError("A construction without its frozen target digest was admitted");
                } catch (HologramStockNativeWire.InvalidConfirmation expected) {
                    // Reading a stage is deliberately weaker than confirming a mutation.
                }
            }
            var oldVersion = (NBTTagCompound) packet.copy();
            oldVersion.setInteger("nativeStockVersion", HologramStockNativeWire.VERSION - 1);
            SimpleAssert.that(
                HologramStockNativeWire.readRequest(oldVersion) == null,
                "retired native protocol cannot acquire new-operation authority");
            var geometry = (NBTTagCompound) packet.copy();
            geometry.setTag("cells", new NBTTagCompound());
            SimpleAssert.that(
                HologramStockNativeWire.readRequest(geometry) == null,
                "client geometry is not accepted alongside native intent");
            var duplicate = (NBTTagCompound) packet.copy();
            duplicate.setIntArray("projectionHatches", new int[] { -1, 0, 1, 44, 2, -1, 0, 1, 45, 2 });
            SimpleAssert.that(
                HologramStockNativeWire.readRequest(duplicate) == null,
                "two hatch choices cannot own the same native position");
            var missing = (NBTTagCompound) packet.copy();
            missing.removeTag("projectionController");
            SimpleAssert.that(
                HologramStockNativeWire.readRequest(missing) == null,
                "missing projection identity never falls back to a per-machine implementation");
            for (Stage stage : Stage.values()) {
                var observation = new ServerObservation(1000, 10, 80, -20, stage, 27, "");
                var reply = new Reply(warm.key(), Phase.CAPABLE, 0, "", observation);
                SimpleAssert.eq(
                    reply,
                    HologramStockNativeWire.readReply(HologramStockNativeWire.writeReply(reply)),
                    "observation preserves its exact action request and target revision without an observation authorization token");
            }
        }
        var forged = new Projection(
            1000,
            Operation.REPAIR,
            Collections.emptyMap(),
            Arrays.asList(new HatchChoice(1, 0, 0, 44, 2), new HatchChoice(1, 0, 0, 45, 3)),
            0,
            1);
        SimpleAssert.that(!forged.valid(), "duplicate positions are rejected before any server work");
    }
}
