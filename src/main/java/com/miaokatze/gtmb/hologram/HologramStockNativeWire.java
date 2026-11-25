// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;

/** One confirmed stock action carried by the old-peer inert serverRules envelope. No nested payload. */
public final class HologramStockNativeWire {

    public static final int VERSION = 4;
    private static final int EBF_HATCHES = 5, EBF_WORDS = 5 * EBF_HATCHES;
    private static final String VERSION_KEY = "nativeStockVersion";
    private static final String REQUEST = "nativeStockRequest";
    private static final String INTENT = "nativeStockIntent";
    private static final String GENERATION = "nativeStockGeneration";
    private static final String COMMAND = "nativeStockCommand";

    public enum Command {
        BEGIN, STOP, STATUS, PROBE, BEGIN_AFTER_PROBE;

        boolean begins() {
            return this == BEGIN || this == BEGIN_AFTER_PROBE;
        }
    }

    public enum Phase {
        ACCEPTED, ACTIVE, COMPLETE, STOPPED, REFUSED, RECOVERY_PENDING, UNKNOWN, CAPABLE, NEEDS_PROOF
    }

    @Desugar
    public record Key(UUID request, long sequence, long generation) {

        public boolean valid() {
            return validNonce() && generation > 0;
        }

        boolean validNonce() {
            return request != null && (request.getMostSignificantBits() != 0 || request.getLeastSignificantBits() != 0)
                && sequence > 0;
        }

        HologramConstructionTaskGate.Request gateRequest() {
            return new HologramConstructionTaskGate.Request(
                request, sequence, HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE);
        }
    }

    /** EBF uses declaration coordinates, exact installed type and facing, not arbitrary requested blocks. */
    @Desugar
    public record EbfHatch(int a, int b, int c, int id, int facing) {}

    /** Client convenience only. The authenticated server independently qualifies all five installed parts. */
    public static List<EbfHatch> observedEbfHatches(World world, TileEntity tile) {
        return HologramStockEbfProfile.observedHatches(world, tile);
    }

    public static boolean ebfChannels(int main, NBTTagCompound channels) {
        return HologramStockEbfProfile.coilMetadata(main, channels) >= 0;
    }

    /** Source IDs are implied by the exact stock profile shape; no client geometry or material list. */
    @Desugar
    public record Confirmation(Key key, int dimension, UUID tool, int x, int y, int z, int side, int slot,
        int facing, int main, boolean mainExplicit, boolean noHatches, int priority,
        int hatchX, int hatchY, int hatchZ, int hatchFacing, int coil, List<EbfHatch> ebfHatches, int sources,
        HologramDeclaredNativeSelection declaration) {
        public Confirmation(Key key,int dimension,UUID tool,int x,int y,int z,int side,int slot,int facing,
            int main,boolean mainExplicit,boolean noHatches,int priority,int hatchX,int hatchY,int hatchZ,int hatchFacing,
            int coil,List<EbfHatch> ebfHatches,int sources) {
            this(key,dimension,tool,x,y,z,side,slot,facing,main,mainExplicit,noHatches,priority,hatchX,hatchY,hatchZ,
                hatchFacing,coil,ebfHatches,sources,null);
        }

        public static Confirmation declared(Key key,int dimension,UUID tool,int x,int y,int z,int side,int slot,
            int facing,int main,boolean mainExplicit,boolean noHatches,int sources,HologramDeclaredNativeSelection declaration) {
            return new Confirmation(key,dimension,tool,x,y,z,side,slot,facing,main,mainExplicit,noHatches,
                (sources>>>3)&3,0,0,0,0,0,null,sources,declaration);
        }
        public Confirmation(Key key,int dimension,UUID tool,int x,int y,int z,int side,int slot,int facing,
            int main,boolean mainExplicit,boolean noHatches,int priority,int hatchX,int hatchY,int hatchZ,int hatchFacing,
            int coil,List<EbfHatch> ebfHatches) {
            this(key,dimension,tool,x,y,z,side,slot,facing,main,mainExplicit,noHatches,priority,hatchX,hatchY,hatchZ,
                hatchFacing,coil,ebfHatches,1 | (priority<<3));
        }
        public Confirmation withSources(int selected) {
            return new Confirmation(key,dimension,tool,x,y,z,side,slot,facing,main,mainExplicit,noHatches,(selected>>>3)&3,
                hatchX,hatchY,hatchZ,hatchFacing,coil,ebfHatches,selected,declaration);
        }

        public Confirmation {
            if (ebfHatches != null) ebfHatches = ebfHatches.size() == EBF_HATCHES
                ? Collections.unmodifiableList(new ArrayList<>(ebfHatches)) : Collections.emptyList();
        }

        public Confirmation(Key key, int dimension, UUID tool, int x, int y, int z, int side, int slot,
            int facing, int main, boolean mainExplicit, boolean noHatches, int priority,
            int hatchX, int hatchY, int hatchZ, int hatchFacing) {
            this(key, dimension, tool, x, y, z, side, slot, facing, main, mainExplicit, noHatches, priority,
                hatchX, hatchY, hatchZ, hatchFacing, 0, null);
        }

        public static Confirmation ebf(Key key, int dimension, UUID tool, int x, int y, int z, int side, int slot,
            int facing, int main, boolean mainExplicit, boolean noHatches, int priority, int coil,
            List<EbfHatch> installed) {
            return new Confirmation(key, dimension, tool, x, y, z, side, slot, facing, main, mainExplicit,
                noHatches, priority, 0, 0, 0, 0, coil, installed == null ? Collections.emptyList() : installed);
        }

        public boolean ebf() {
            return ebfHatches != null;
        }

        HologramStockNativeEntry.Intent intent() {
            if (declaration != null)
                return new HologramStockNativeEntry.Intent(key.gateRequest(), key.generation,
                    HologramStockNativeEntry.DECLARED, declaration.controller(), x, y, z, side, slot, facing,
                    main, mainExplicit, noHatches, priority, HologramStockNativeEntry.Funding.REQUIRE_FULL_BOUND,
                    null, null, sources, declaration);
            if (ebf()) {
                var installed = new ArrayList<HologramStockEbfProfile.Installed>(EBF_HATCHES);
                for (var hatch : ebfHatches) {
                    if (hatch == null) return null;
                    installed.add(new HologramStockEbfProfile.Installed(hatch.a, hatch.b, hatch.c, hatch.id, hatch.facing));
                }
                return new HologramStockNativeEntry.Intent(key.gateRequest(), key.generation,
                    HologramStockNativeEntry.EBF, HologramStockEbfProfile.CONTROLLER,
                    x, y, z, side, slot, facing, main, mainExplicit, noHatches, priority,
                    HologramStockNativeEntry.Funding.REQUIRE_FULL_BOUND, null,
                    new HologramStockNativeEntry.EbfConfig(coil, installed), sources);
            }
            return new HologramStockNativeEntry.Intent(key.gateRequest(), key.generation,
                HologramStockCokeOvenProfile.SOURCE_ID, HologramStockCokeOvenProfile.CONTROLLER,
                x, y, z, side, slot, facing, main, mainExplicit, noHatches, priority,
                HologramStockNativeEntry.Funding.REQUIRE_FULL_BOUND,
                new HologramStockNativeConstruction.HatchChoice(hatchX, hatchY, hatchZ,
                    HologramStockCokeOvenProfile.HATCH, HologramStockCokeOvenProfile.HATCH_TIER, hatchFacing), null, sources);
        }

        public Confirmation forGeneration(long generation) {
            return new Confirmation(new Key(key.request, key.sequence, generation), dimension, tool, x, y, z, side,
                slot, facing, main, mainExplicit, noHatches, priority, hatchX, hatchY, hatchZ, hatchFacing, coil, ebfHatches, sources, declaration);
        }

        boolean valid(boolean probe) {
            if (key == null || !key.validNonce() || (probe ? key.generation != 0 : key.generation <= 0)
                || tool == null || (tool.getMostSignificantBits() == 0 && tool.getLeastSignificantBits() == 0))
                return false;
            if (declaration != null && (ebf() || hatchX != 0 || hatchY != 0 || hatchZ != 0 || hatchFacing != 0)) return false;
            if (ebf() ? hatchX != 0 || hatchY != 0 || hatchZ != 0 || hatchFacing != 0 : coil != 0) return false;
            var checked = (probe ? forGeneration(1) : this).intent();
            return checked != null && checked.valid();
        }
    }

    @Desugar
    record Request(Command command, Key key, Confirmation confirmation) {}

    @Desugar
    public record Reply(Key key, Phase phase, int placed, String reason) {}

    private HologramStockNativeWire() {}

    static boolean recognizes(NBTTagCompound tag) {
        return tag != null && tag.hasKey(VERSION_KEY);
    }

    public static NBTTagCompound confirm(Confirmation value) {
        return preparedAction(value, Command.BEGIN);
    }

    public static NBTTagCompound confirmAfterProbe(Confirmation value) {
        return preparedAction(value, Command.BEGIN_AFTER_PROBE);
    }

    public static NBTTagCompound probe(Confirmation pending) {
        return preparedAction(pending, Command.PROBE);
    }

    private static NBTTagCompound preparedAction(Confirmation value, Command command) {
        if (value == null || !value.valid(command == Command.PROBE))
            throw new IllegalArgumentException("Invalid stock native confirmation");
        var tag = action(value.key, command);
        tag.setString("tool", value.tool.toString());
        tag.setInteger("dimension", value.dimension);
        tag.setInteger("x", value.x);
        tag.setInteger("y", value.y);
        tag.setInteger("z", value.z);
        tag.setInteger("side", value.side);
        tag.setInteger("slot", value.slot);
        tag.setInteger("facing", value.facing);
        tag.setInteger("main", value.main);
        tag.setBoolean("mainExplicit", value.mainExplicit);
        tag.setBoolean("noHatches", value.noHatches);
        tag.setInteger("priority", value.priority);
        tag.setInteger("sources",value.sources);
        if (value.declaration != null) {
            var selection = value.declaration;
            tag.setInteger("controller", selection.controller());
            tag.setInteger("scope", selection.scope());
            tag.setInteger("layer", selection.layer());
            if (selection.operation() != 0) tag.setInteger("operation", selection.operation());
            if (selection.operation() == 2) tag.setInteger("demolitionDestination", selection.destination());
            tag.setTag("channels", selection.channelTag());
            int[] selected = new int[selection.selected().size() * 3];
            int i = 0;
            for (var position : selection.selected()) {
                selected[i++] = position.x();
                selected[i++] = position.y();
                selected[i++] = position.z();
            }
            tag.setIntArray("selected", selected);
            int[] hatches = new int[selection.hatches().size() * 5];
            i = 0;
            for (var hatch : selection.hatches()) {
                hatches[i++] = hatch.position().x();
                hatches[i++] = hatch.position().y();
                hatches[i++] = hatch.position().z();
                hatches[i++] = hatch.id();
                hatches[i++] = hatch.facing();
            }
            tag.setIntArray("hatches", hatches);
        } else if (value.ebf()) {
            tag.setInteger("coil", value.coil);
            int[] hatches = new int[EBF_WORDS];
            int i = 0;
            for (var hatch : value.ebfHatches) {
                hatches[i++] = hatch.a;
                hatches[i++] = hatch.b;
                hatches[i++] = hatch.c;
                hatches[i++] = hatch.id;
                hatches[i++] = hatch.facing;
            }
            tag.setIntArray("installedHatches", hatches);
        } else {
            tag.setInteger("hatchX", value.hatchX);
            tag.setInteger("hatchY", value.hatchY);
            tag.setInteger("hatchZ", value.hatchZ);
            tag.setInteger("hatchFacing", value.hatchFacing);
        }
        return tag;
    }

    public static NBTTagCompound control(Key key, Command command) {
        if (command != Command.STOP && command != Command.STATUS) throw new IllegalArgumentException("Expected stock control");
        return action(key, command);
    }

    private static NBTTagCompound action(Key key, Command command) {
        var tag = identity(key, command == Command.PROBE);
        tag.setString("action", "serverRules");
        tag.setByte(COMMAND, (byte) command.ordinal());
        return tag;
    }

    static Request readRequest(NBTTagCompound tag) {
        if (!tag.hasKey("action", 8) || !"serverRules".equals(tag.getString("action"))
            || !tag.hasKey(COMMAND, 1)) return null;
        int index = tag.getByte(COMMAND);
        if (index < 0 || index >= Command.values().length) return null;
        Command command = Command.values()[index];
        var key = readIdentity(tag, command == Command.PROBE);
        if (key == null) return null;
        if (command == Command.STOP || command == Command.STATUS)
            return tag.func_150296_c().size() == 6 ? new Request(command, key, null) : null;

        boolean declared = tag.hasKey("controller"), ebf = tag.hasKey("installedHatches");
        int extra = declared && tag.hasKey("operation") ? (tag.hasKey("demolitionDestination") ? 2 : 1) : 0;
        if (tag.func_150296_c().size() != (declared ? 25 + extra : ebf ? 21 : 23) || !tag.hasKey("tool", 8)
            || !exactBoolean(tag, "mainExplicit") || !exactBoolean(tag, "noHatches")) return null;
        for (String field : new String[] { "dimension", "x", "y", "z", "side", "slot", "facing", "main", "priority","sources" })
            if (!tag.hasKey(field, 3)) return null;
        List<EbfHatch> installed = null;
        HologramDeclaredNativeSelection declaration = null;
        if (declared) {
            declaration = readDeclaration(tag);
            if (declaration == null) return null;
        } else if (ebf) {
            if (!tag.hasKey("installedHatches", 11) || !tag.hasKey("coil", 3)) return null;
            int[] hatches = tag.getIntArray("installedHatches");
            if (hatches.length != EBF_WORDS) return null;
            installed = new ArrayList<>(EBF_HATCHES);
            for (int i = 0; i < hatches.length; i += 5)
                installed.add(new EbfHatch(hatches[i], hatches[i + 1], hatches[i + 2], hatches[i + 3], hatches[i + 4]));
        } else {
            for (String field : new String[] { "hatchX", "hatchY", "hatchZ", "hatchFacing" })
                if (!tag.hasKey(field, 3)) return null;
        }
        var confirmation = new Confirmation(key, tag.getInteger("dimension"), uuid(tag.getString("tool")),
            tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"), tag.getInteger("side"),
            tag.getInteger("slot"), tag.getInteger("facing"), tag.getInteger("main"), tag.getBoolean("mainExplicit"),
            tag.getBoolean("noHatches"), tag.getInteger("priority"), tag.getInteger("hatchX"),
            tag.getInteger("hatchY"), tag.getInteger("hatchZ"), tag.getInteger("hatchFacing"),
            tag.getInteger("coil"), installed,tag.getInteger("sources"), declaration);
        return confirmation.valid(command == Command.PROBE) ? new Request(command, key, confirmation) : null;
    }

    private static HologramDeclaredNativeSelection readDeclaration(NBTTagCompound tag) {
        for (String field : new String[] { "controller", "scope", "layer" }) if (!tag.hasKey(field, 3)) return null;
        if (!tag.hasKey("channels", 10) || !tag.hasKey("selected", 11) || !tag.hasKey("hatches", 11)) return null;
        var channels = tag.getCompoundTag("channels");
        if (!HologramSessionPolicy.validChannels(channels)) return null;
        var named = new LinkedHashMap<String, Integer>();
        for (Object key : channels.func_150296_c()) named.put((String) key, channels.getInteger((String) key));
        int[] points = tag.getIntArray("selected"), edits = tag.getIntArray("hatches");
        if (points.length % 3 != 0 || edits.length % 5 != 0
            || points.length / 3 > HologramDeclaredNativeSelection.MAX_POINTS
            || edits.length / 5 > HologramDeclaredNativeSelection.MAX_POINTS) return null;
        var selected = new ArrayList<HologramDeclaredNativeSelection.Offset>();
        var hatches = new ArrayList<HologramDeclaredNativeSelection.Hatch>();
        for (int i = 0; i < points.length; i += 3)
            selected.add(new HologramDeclaredNativeSelection.Offset(points[i], points[i + 1], points[i + 2]));
        for (int i = 0; i < edits.length; i += 5)
            hatches.add(new HologramDeclaredNativeSelection.Hatch(
                new HologramDeclaredNativeSelection.Offset(edits[i], edits[i + 1], edits[i + 2]), edits[i + 3], edits[i + 4]));
        int operation = 0, destination = -1;
        if (tag.hasKey("operation")) {
            if (!tag.hasKey("operation", 3)) return null;
            operation = tag.getInteger("operation");
            if (operation != 1 && operation != 2) return null;
        }
        if (operation == 2) {
            if (!tag.hasKey("demolitionDestination", 3)) return null;
            destination = tag.getInteger("demolitionDestination");
        } else if (tag.hasKey("demolitionDestination")) return null;
        var result = new HologramDeclaredNativeSelection(tag.getInteger("controller"), named,
            tag.getInteger("scope"), tag.getInteger("layer"), selected, hatches, operation, destination);
        return result.valid() ? result : null;
    }

    static NBTTagCompound writeReply(Reply reply) {
        var tag = identity(reply.key, false);
        tag.setBoolean("serverRulesOnly", true);
        tag.setByte("nativeStockPhase", (byte) reply.phase.ordinal());
        tag.setInteger("placed", reply.placed);
        String reason = reply.reason;
        tag.setString("reason", reason.substring(0, Math.min(reason.length(), 192)));
        return tag;
    }

    static Reply readReply(NBTTagCompound tag) {
        if (tag.func_150296_c().size() != 8 || !exactBoolean(tag, "serverRulesOnly")
            || !tag.getBoolean("serverRulesOnly") || !tag.hasKey("nativeStockPhase", 1)
            || !tag.hasKey("placed", 3) || !tag.hasKey("reason", 8)) return null;
        var key = readIdentity(tag, false);
        int phase = tag.getByte("nativeStockPhase"), placed = tag.getInteger("placed");
        String reason = tag.getString("reason");
        if (key == null || phase < 0 || phase >= Phase.values().length || reason.length() > 192) return null;
        Phase state = Phase.values()[phase];
        if (state == Phase.UNKNOWN ? placed != -1 : placed < 0 || placed > HologramBlueprintLimits.MAX_CELLS || (state == Phase.CAPABLE || state == Phase.NEEDS_PROOF) && placed != 0)
            return null;
        return new Reply(key, state, placed, reason);
    }

    private static NBTTagCompound identity(Key key, boolean probe) {
        if (key == null || !key.validNonce() || (probe ? key.generation != 0 : key.generation <= 0))
            throw new IllegalArgumentException("Invalid stock native request key");
        var tag = new NBTTagCompound();
        tag.setInteger(VERSION_KEY, VERSION);
        tag.setString(REQUEST, key.request.toString());
        tag.setLong(INTENT, key.sequence);
        tag.setLong(GENERATION, key.generation);
        return tag;
    }

    private static Key readIdentity(NBTTagCompound tag, boolean probe) {
        if (!tag.hasKey(VERSION_KEY, 3) || tag.getInteger(VERSION_KEY) != VERSION || !tag.hasKey(REQUEST, 8)
            || !tag.hasKey(INTENT, 4) || !tag.hasKey(GENERATION, 4)) return null;
        var key = new Key(uuid(tag.getString(REQUEST)), tag.getLong(INTENT), tag.getLong(GENERATION));
        return key.validNonce() && (probe ? key.generation == 0 : key.generation > 0) ? key : null;
    }

    private static boolean exactBoolean(NBTTagCompound tag, String key) {
        return tag.hasKey(key, 1) && (tag.getByte(key) == 0 || tag.getByte(key) == 1);
    }

    private static UUID uuid(String text) {
        return HologramNativeCompactStart.canonical(text) ? UUID.fromString(text) : null;
    }
}
