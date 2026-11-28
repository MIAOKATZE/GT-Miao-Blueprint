// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;

/** Authenticated native-projector intent only. The server rebuilds the complete target independently. */
public final class HologramStockNativeWire {

    public static final int VERSION = 6;
    private static final String VERSION_KEY = "nativeStockVersion", REQUEST = "nativeStockRequest",
        INTENT = "nativeStockIntent", COMMAND = "nativeStockCommand";

    public enum Command {

        BEGIN,
        STOP,
        STATUS,
        PROBE;

        boolean begins() {
            return this == BEGIN;
        }
    }

    /** Native structure actions are independent from the blueprint editor's legacy mode. */
    public enum Operation {
        BUILD,
        REPAIR,
        REPLACE,
        DEMOLISH
    }

    public enum Stage {
        CONTROLLER_ONLY,
        UNFORMED,
        FORMED,
        UNKNOWN
    }

    @Desugar
    public record ServerObservation(int controller, int x, int y, int z, Stage stage, long targetRevision,
        String reason) {}

    @Desugar
    public record HatchChoice(int dx, int dy, int dz, int id, int facing) {}

    @Desugar
    public record Projection(int controller, Operation operation, Map<String, Integer> channels,
        List<HatchChoice> hatches, int destination, long targetRevision, NBTTagCompound configuration,
        String nativeTargetDigest) {

        public Projection(int controller, Operation operation, Map<String, Integer> channels, List<HatchChoice> hatches,
            int destination, long targetRevision) {
            this(controller, operation, channels, hatches, destination, targetRevision, new NBTTagCompound(), "");
        }

        public Projection(int controller, Operation operation, Map<String, Integer> channels, List<HatchChoice> hatches,
            int destination, long targetRevision, NBTTagCompound configuration) {
            this(controller, operation, channels, hatches, destination, targetRevision, configuration, "");
        }

        public Projection {
            nativeTargetDigest = nativeTargetDigest == null ? "" : nativeTargetDigest;
            configuration = configuration == null ? new NBTTagCompound() : (NBTTagCompound) configuration.copy();
            channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
            hatches = Collections.unmodifiableList(new ArrayList<>(hatches));
        }

        public NBTTagCompound configuration() {
            return (NBTTagCompound) configuration.copy();
        }

        public Projection withConfiguration(NBTTagCompound config) {
            return new Projection(
                controller,
                operation,
                channels,
                hatches,
                destination,
                targetRevision,
                config,
                nativeTargetDigest);
        }

        public Projection withNativeTargetDigest(String digest) {
            return new Projection(
                controller,
                operation,
                channels,
                hatches,
                destination,
                targetRevision,
                configuration,
                digest);
        }

        boolean valid() {
            if (!nativeTargetDigest.isEmpty() && !nativeTargetDigest.matches("[0-9a-f]{64}")) return false;
            for (Object name : configuration.func_150296_c())
                if (!"presetActive".equals(name) && !"hatchTier".equals(name)
                    && !"hatchDowngrade".equals(name)
                    && !"hatchProfile".equals(name)) return false;
            if (configuration.toString()
                .length() > 65536) return false;
            if (controller <= 0 || controller > Short.MAX_VALUE
                || operation == null
                || targetRevision <= 0
                || destination < 0
                || destination > 1
                || channels.size() > 64
                || hatches.size() > 128) return false;
            var tag = new NBTTagCompound();
            for (var entry : channels.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) return false;
                tag.setInteger(entry.getKey(), entry.getValue());
            }
            if (!HologramSessionPolicy.validChannels(tag)) return false;
            var positions = new HashSet<String>();
            for (var choice : hatches) if (choice == null || choice.id <= 0
                || choice.id > Short.MAX_VALUE
                || choice.facing < -1
                || choice.facing > 5
                || Math.abs((long) choice.dx) > 4096
                || Math.abs((long) choice.dy) > 4096
                || Math.abs((long) choice.dz) > 4096
                || !positions.add(choice.dx + "," + choice.dy + "," + choice.dz)) return false;
            return true;
        }
    }

    public enum Phase {
        ACCEPTED,
        ACTIVE,
        COMPLETE,
        STOPPED,
        REFUSED,
        RECOVERY_PENDING,
        UNKNOWN,
        CAPABLE
    }

    @Desugar
    public record Key(UUID request, long sequence) {

        public boolean valid() {
            return request != null && (request.getMostSignificantBits() != 0 || request.getLeastSignificantBits() != 0)
                && sequence > 0;
        }

        boolean validNonce() {
            return valid();
        }
    }

    @Desugar
    public record Confirmation(Key key, int dimension, UUID tool, int x, int y, int z, int side, int slot, int facing,
        int main, boolean mainExplicit, boolean noHatches, int sources, Projection projection) {

        public static Confirmation projection(Key key, int dimension, UUID tool, int x, int y, int z, int side,
            int slot, int facing, int main, boolean mainExplicit, boolean noHatches, int sources, Projection target) {
            return new Confirmation(
                key,
                dimension,
                tool,
                x,
                y,
                z,
                side,
                slot,
                facing,
                main,
                mainExplicit,
                noHatches,
                sources,
                target);
        }

        public int priority() {
            return (sources >>> 3) & 3;
        }

        public Confirmation withSources(int selected) {
            return new Confirmation(
                key,
                dimension,
                tool,
                x,
                y,
                z,
                side,
                slot,
                facing,
                main,
                mainExplicit,
                noHatches,
                selected,
                projection);
        }

        HologramStockNativeEntry.Intent intent() {
            return new HologramStockNativeEntry.Intent(
                key,
                x,
                y,
                z,
                side,
                slot,
                facing,
                main,
                mainExplicit,
                noHatches,
                sources,
                projection);
        }

        boolean valid(boolean probe) {
            return key != null && key.validNonce()
                && tool != null
                && (tool.getMostSignificantBits() != 0 || tool.getLeastSignificantBits() != 0)
                && intent().valid()
                && (probe || projection.operation() == Operation.DEMOLISH
                    || !projection.nativeTargetDigest()
                        .isEmpty());
        }
    }

    @Desugar
    record Request(Command command, Key key, Confirmation confirmation) {}

    @Desugar
    public record Reply(Key key, Phase phase, int placed, String reason, ServerObservation observation) {

        public Reply(Key key, Phase phase, int placed, String reason) {
            this(key, phase, placed, reason, null);
        }
    }

    private HologramStockNativeWire() {}

    static boolean recognizes(NBTTagCompound tag) {
        return tag != null && tag.hasKey(VERSION_KEY);
    }

    public static NBTTagCompound confirm(Confirmation value) {
        return preparedAction(value, Command.BEGIN);
    }

    public static NBTTagCompound probe(Confirmation value) {
        return preparedAction(value, Command.PROBE);
    }

    public static final class InvalidConfirmation extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private InvalidConfirmation() {
            super("Invalid native projection confirmation");
        }
    }

    private static NBTTagCompound preparedAction(Confirmation value, Command command) {
        if (value == null || !value.valid(command == Command.PROBE)) throw new InvalidConfirmation();
        var tag = action(value.key(), command);
        tag.setString(
            "tool",
            value.tool()
                .toString());
        tag.setInteger("dimension", value.dimension());
        tag.setInteger("x", value.x());
        tag.setInteger("y", value.y());
        tag.setInteger("z", value.z());
        tag.setInteger("side", value.side());
        tag.setInteger("slot", value.slot());
        tag.setInteger("facing", value.facing());
        tag.setInteger("main", value.main());
        tag.setBoolean("mainExplicit", value.mainExplicit());
        tag.setBoolean("noHatches", value.noHatches());
        tag.setInteger("priority", value.priority());
        tag.setInteger("sources", value.sources());
        var target = value.projection();
        tag.setString("nativeTargetDigest", target.nativeTargetDigest());
        tag.setTag("projectionConfiguration", target.configuration());
        tag.setInteger("projectionController", target.controller());
        tag.setInteger(
            "projectionOperation",
            target.operation()
                .ordinal());
        tag.setLong("targetRevision", target.targetRevision());
        tag.setInteger("destination", target.destination());
        var channels = new NBTTagCompound();
        target.channels()
            .forEach(channels::setInteger);
        tag.setTag("projectionChannels", channels);
        int[] choices = new int[target.hatches()
            .size() * 5];
        int n = 0;
        for (var choice : target.hatches()) {
            choices[n++] = choice.dx();
            choices[n++] = choice.dy();
            choices[n++] = choice.dz();
            choices[n++] = choice.id();
            choices[n++] = choice.facing();
        }
        tag.setIntArray("projectionHatches", choices);
        return tag;
    }

    public static NBTTagCompound control(Key key, Command command) {
        if (command != Command.STOP && command != Command.STATUS)
            throw new IllegalArgumentException("Expected native control");
        return action(key, command);
    }

    private static NBTTagCompound action(Key key, Command command) {
        var tag = identity(key, command == Command.PROBE);
        tag.setString("action", "serverRules");
        tag.setByte(COMMAND, (byte) command.ordinal());
        return tag;
    }

    static Request readRequest(NBTTagCompound tag) {
        if (tag == null || !tag.hasKey("action", 8)
            || !"serverRules".equals(tag.getString("action"))
            || !tag.hasKey(COMMAND, 1)) return null;
        int index = tag.getByte(COMMAND);
        if (index < 0 || index >= Command.values().length) return null;
        var command = Command.values()[index];
        var key = readIdentity(tag, command == Command.PROBE);
        if (key == null) return null;
        if (command == Command.STOP || command == Command.STATUS) return tag.func_150296_c()
            .size() == 5 ? new Request(command, key, null) : null;
        return readProjectionRequest(tag, command, key);
    }

    private static Request readProjectionRequest(NBTTagCompound tag, Command command, Key key) {
        if (tag.func_150296_c()
            .size() != 26 || !tag.hasKey("projectionConfiguration", 10)
            || !tag.hasKey("tool", 8)
            || !tag.hasKey("nativeTargetDigest", 8)
            || !exactBoolean(tag, "mainExplicit")
            || !exactBoolean(tag, "noHatches")) return null;
        for (String field : new String[] { "dimension", "x", "y", "z", "side", "slot", "facing", "main", "priority",
            "sources", "projectionController", "projectionOperation", "destination" })
            if (!tag.hasKey(field, 3)) return null;
        if (!tag.hasKey("targetRevision", 4) || !tag.hasKey("projectionChannels", 10)
            || !tag.hasKey("projectionHatches", 11)) return null;
        int operation = tag.getInteger("projectionOperation");
        if (operation < 0 || operation >= Operation.values().length) return null;
        var channels = tag.getCompoundTag("projectionChannels");
        if (!HologramSessionPolicy.validChannels(channels)) return null;
        var named = new LinkedHashMap<String, Integer>();
        for (Object name : channels.func_150296_c()) named.put((String) name, channels.getInteger((String) name));
        var edits = tag.getIntArray("projectionHatches");
        if (edits.length % 5 != 0 || edits.length > 128 * 5) return null;
        var choices = new ArrayList<HatchChoice>();
        for (int i = 0; i < edits.length; i += 5)
            choices.add(new HatchChoice(edits[i], edits[i + 1], edits[i + 2], edits[i + 3], edits[i + 4]));
        var target = new Projection(
            tag.getInteger("projectionController"),
            Operation.values()[operation],
            named,
            choices,
            tag.getInteger("destination"),
            tag.getLong("targetRevision"),
            tag.getCompoundTag("projectionConfiguration"),
            tag.getString("nativeTargetDigest"));
        var confirm = Confirmation.projection(
            key,
            tag.getInteger("dimension"),
            uuid(tag.getString("tool")),
            tag.getInteger("x"),
            tag.getInteger("y"),
            tag.getInteger("z"),
            tag.getInteger("side"),
            tag.getInteger("slot"),
            tag.getInteger("facing"),
            tag.getInteger("main"),
            tag.getBoolean("mainExplicit"),
            tag.getBoolean("noHatches"),
            tag.getInteger("sources"),
            target);
        return confirm.priority() == tag.getInteger("priority") && confirm.valid(command == Command.PROBE)
            ? new Request(command, key, confirm)
            : null;
    }

    static NBTTagCompound writeReply(Reply reply) {
        var tag = identity(reply.key, false);
        tag.setBoolean("serverRulesOnly", true);
        tag.setByte("nativeStockPhase", (byte) reply.phase.ordinal());
        tag.setInteger("placed", reply.placed);
        String reason = reply.reason;
        tag.setString("reason", reason.substring(0, Math.min(reason.length(), 192)));
        if (reply.observation != null) {
            var observed = reply.observation;
            var data = new NBTTagCompound();
            data.setInteger("controller", observed.controller);
            data.setInteger("x", observed.x);
            data.setInteger("y", observed.y);
            data.setInteger("z", observed.z);
            data.setInteger("stage", observed.stage.ordinal());
            data.setLong("targetRevision", observed.targetRevision);
            data.setString("reason", observed.reason);
            tag.setTag("projectionObservation", data);
        }
        return tag;
    }

    static Reply readReply(NBTTagCompound tag) {
        if (tag.func_150296_c()
            .size() != (tag.hasKey("projectionObservation", 10) ? 8 : 7) || !exactBoolean(tag, "serverRulesOnly")
            || !tag.getBoolean("serverRulesOnly")
            || !tag.hasKey("nativeStockPhase", 1)
            || !tag.hasKey("placed", 3)
            || !tag.hasKey("reason", 8)) return null;
        var key = readIdentity(tag, false);
        int phase = tag.getByte("nativeStockPhase"), placed = tag.getInteger("placed");
        String reason = tag.getString("reason");
        if (key == null || phase < 0 || phase >= Phase.values().length || reason.length() > 192) return null;
        Phase state = Phase.values()[phase];
        if (state == Phase.UNKNOWN ? placed != -1
            : placed < 0 || placed > HologramBlueprintLimits.MAX_CELLS || (state == Phase.CAPABLE) && placed != 0)
            return null;
        ServerObservation observation = null;
        if (tag.hasKey("projectionObservation")) {
            if (!tag.hasKey("projectionObservation", 10)) return null;
            var data = tag.getCompoundTag("projectionObservation");
            for (String field : new String[] { "controller", "x", "y", "z", "stage" })
                if (!data.hasKey(field, 3)) return null;
            int stage = data.getInteger("stage");
            if (data.func_150296_c()
                .size() != 7 || stage < 0
                || stage >= Stage.values().length
                || !data.hasKey("targetRevision", 4)
                || data.getLong("targetRevision") <= 0
                || !data.hasKey("reason", 8)
                || data.getString("reason")
                    .length() > 192)
                return null;
            observation = new ServerObservation(
                data.getInteger("controller"),
                data.getInteger("x"),
                data.getInteger("y"),
                data.getInteger("z"),
                Stage.values()[stage],
                data.getLong("targetRevision"),
                data.getString("reason"));
        }
        return new Reply(key, state, placed, reason, observation);
    }

    private static NBTTagCompound identity(Key key, boolean probe) {
        if (key == null || !key.valid()) throw new IllegalArgumentException("Invalid stock native request key");
        var tag = new NBTTagCompound();
        tag.setInteger(VERSION_KEY, VERSION);
        tag.setString(REQUEST, key.request.toString());
        tag.setLong(INTENT, key.sequence);
        return tag;
    }

    private static Key readIdentity(NBTTagCompound tag, boolean probe) {
        if (!tag.hasKey(VERSION_KEY, 3) || tag.getInteger(VERSION_KEY) != VERSION
            || !tag.hasKey(REQUEST, 8)
            || !tag.hasKey(INTENT, 4)) return null;
        var key = new Key(uuid(tag.getString(REQUEST)), tag.getLong(INTENT));
        return key.valid() ? key : null;
    }

    private static boolean exactBoolean(NBTTagCompound tag, String key) {
        return tag.hasKey(key, 1) && (tag.getByte(key) == 0 || tag.getByte(key) == 1);
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text)
                .toString()
                .equals(text) ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
