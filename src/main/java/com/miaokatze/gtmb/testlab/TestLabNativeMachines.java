package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Four explicitly selected native structures, kept separate from the registered item catalog. */
final class TestLabNativeMachines {

    private static final String PREFIX = "machine-";
    private static final Spec[] SPECS = {
        new Spec(
            "forge-of-gods",
            "tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods",
            127,
            29,
            187,
            63,
            14,
            1,
            144,
            64,
            208),
        new Spec(
            "bec-observation",
            "tectech.thing.metaTileEntity.multi.bec.MTEBECAssembler",
            61,
            31,
            31,
            30,
            15,
            1,
            80,
            64,
            64),
        new Spec(
            "electric-blast-furnace",
            "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace",
            3,
            4,
            3,
            1,
            3,
            0,
            32,
            16,
            32),
        new Spec(
            "large-chemical-reactor",
            "gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor",
            3,
            3,
            3,
            1,
            1,
            0,
            32,
            16,
            32) };

    private final TestLabRuntime runtime;
    private final List<Machine> machines = new ArrayList<>();
    private int machineIndex, phase, cursor;
    private MessageDigest digest;
    private int solid, tiles;

    TestLabNativeMachines(TestLabRuntime runtime) throws IOException {
        this.runtime = runtime;
        for (String requested : runtime.config().nativeMachines) {
            Spec selected = null;
            int index = 0;
            for (int i = 0; i < SPECS.length; i++) if (SPECS[i].id.equals(requested)) {
                selected = SPECS[i];
                index = i;
            }
            if (selected == null) throw new IOException("Unknown native machine platform: " + requested);
            int z = (runtime.config().zones.get(0).z + 511) & ~511;
            Machine machine = new Machine(
                selected,
                runtime.config()
                    .nativeMachineBaseX(),
                runtime.config().zones.get(0).y,
                z + index * 1024);
            machine.claim = runtime.ownership()
                .nativeMachineClaim(machine.id, machine.layout(), Collections.singletonList(machine.zone));
            NBTTagCompound previous = runtime.ownership()
                .nativeMachineManifest(machine.id);
            if (previous != null && "READY".equals(previous.getString("state"))) machine.data = previous;
            machines.add(machine);
        }
    }

    int buildStep() throws Exception {
        if (machineIndex == machines.size()) return TestLabTasks.DONE;
        Machine machine = machines.get(machineIndex);
        if (phase == 0) {
            runtime.useNativeZones(machine.zone, machine.zone);
            digest = MessageDigest.getInstance("SHA-256");
            solid = tiles = cursor = 0;
            phase = machine.data == null ? 1 : 3;
            return TestLabTasks.WAIT;
        }
        if (phase == 1) {
            if (cursor < machine.volume()) {
                int[] p = machine.cell(cursor++);
                runtime.requireOwned(p[0], p[1], p[2], 1, 1, 1);
                TestLabTasks.require(
                    runtime.world()
                        .isAirBlock(p[0], p[1], p[2]),
                    "Incomplete native platform is occupied; do not adopt or overwrite it: " + machine.id);
                return TestLabTasks.CONTINUE;
            }
            // Same fixed safe corner as TestLabActor.atNative; never inside the structure capture box.
            for (int x = 1; x <= 3; x++) for (int z = 28; z <= 30; z++) {
                runtime.requireOwned(machine.zone.x + x, machine.zone.y, machine.zone.z + z, 1, 1, 1);
                runtime.world()
                    .setBlock(machine.zone.x + x, machine.zone.y, machine.zone.z + z, Blocks.glass, 0, 2);
            }
            phase = 2;
            return TestLabTasks.WAIT;
        }
        if (phase == 2) {
            construct(machine);
            cursor = 0;
            phase = 3;
            return TestLabTasks.WAIT;
        }
        if (cursor < machine.volume()) {
            int[] p = machine.cell(cursor++);
            runtime.requireOwned(p[0], p[1], p[2], 1, 1, 1);
            Block block = runtime.world()
                .getBlock(p[0], p[1], p[2]);
            if (!block.isAir(runtime.world(), p[0], p[1], p[2])) {
                solid++;
                TileEntity tile = runtime.world()
                    .getTileEntity(p[0], p[1], p[2]);
                if (tile != null) tiles++;
                String line = (p[0] - machine.first[0]) + ","
                    + (p[1] - machine.first[1])
                    + ","
                    + (p[2] - machine.first[2])
                    + ":"
                    + Block.blockRegistry.getNameForObject(block)
                    + ":"
                    + runtime.world()
                        .getBlockMetadata(p[0], p[1], p[2])
                    + ":"
                    + (tile == null ? ""
                        : tile.getClass()
                            .getName())
                    + ":"
                    + (tile instanceof BaseMetaTileEntity ? ((BaseMetaTileEntity) tile).getMetaTileID() : 0)
                    + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return TestLabTasks.CONTINUE;
        }
        String hash = hex(digest.digest());
        TestLabTasks.require(solid > 1 && tiles >= 1, "Native construct produced no structure body: " + machine.id);
        if (machine.data.hasKey("structuralSha256")) {
            TestLabTasks.require(
                machine.data.getString("structuralSha256")
                    .equals(hash) && machine.data.getInteger("expectedSolid") == solid
                    && machine.data.getInteger("expectedTileEntities") == tiles,
                "Persisted native machine structure changed: " + machine.id);
        }
        BaseMetaTileEntity controller = controller(machine);
        TestLabTasks.require(
            !controller.isAllowedToWork() && !controller.isActive(),
            "Native fixture must remain inactive, without simulated progress: " + machine.id);
        machine.data.setString("state", "READY");
        machine.data.setInteger("expectedSolid", solid);
        machine.data.setInteger("expectedTileEntities", tiles);
        machine.data.setString("structuralSha256", hash);
        machine.data.setString("sourceFingerprint", hash);
        machine.data.setBoolean("naturalFormed", ((MTEMultiBlockBase) controller.getMetaTileEntity()).mMachine);
        machine.data.setBoolean("controllerWorking", controller.isAllowedToWork());
        TestLabTasks.require(
            runtime.world().theChunkProviderServer.saveChunks(true, null),
            "Native machine chunks could not be queued for saving");
        runtime.ownership()
            .nativeMachineManifest(machine.id, machine.data);
        runtime.evidence()
            .count("nativeMachinePlatforms", 1);
        phase = cursor = 0;
        machineIndex++;
        return TestLabTasks.WAIT;
    }

    private void construct(Machine machine) throws Exception {
        IMetaTileEntity prototype = null;
        for (IMetaTileEntity candidate : GregTechAPI.METATILEENTITIES)
            if (candidate != null && machine.spec.type.equals(
                candidate.getClass()
                    .getName())) {
                        TestLabTasks.require(prototype == null, "Native fixture class has ambiguous registrations");
                        prototype = candidate;
                    }
        TestLabTasks.require(prototype != null, "Native machine registration is unavailable: " + machine.spec.type);
        ItemStack item = prototype.getStackForm(1);
        TestLabTasks.require(item != null && item.getItem() instanceof ItemMachines, "Actual GT machine item required");
        int[] p = machine.controller;
        runtime.requireOwned(p[0], p[1], p[2], 1, 1, 1);
        TestLabTasks.require(
            ((ItemMachines) item.getItem()).placeBlockAt(
                item,
                runtime.actor().player,
                runtime.world(),
                p[0],
                p[1],
                p[2],
                ForgeDirection.NORTH.ordinal(),
                .5F,
                .5F,
                .5F,
                0),
            "Native controller placement refused");
        BaseMetaTileEntity base = controller(machine);
        TestLabTasks.require(
            machine.spec.type.equals(
                base.getMetaTileEntity()
                    .getClass()
                    .getName()),
            "Native controller factory returned another machine");
        base.setFrontFacing(ForgeDirection.NORTH);
        base.setOwnerName(runtime.actor().player.getCommandSenderName());
        base.setOwnerUuid(runtime.actor().player.getUniqueID());
        base.disableWorking();
        Object nativeMachine = base.getMetaTileEntity();
        TestLabTasks.require(
            nativeMachine instanceof IAlignment && nativeMachine instanceof IConstructable,
            "Native structure construction/alignment contract unavailable");
        ((IAlignment) nativeMachine).setExtendedFacing(ExtendedFacing.NORTH_NORMAL_NONE);
        verifyDefinition(machine, nativeMachine);
        ItemStack trigger = item.copy();
        trigger.stackSize = 1;
        long before = System.nanoTime();
        ((IConstructable) nativeMachine).construct(trigger, false);
        long elapsed = System.nanoTime() - before;
        base.disableWorking();
        base.markDirty();
        machine.data = machine.tag();
        machine.data.setLong("nativeConstructNanos", elapsed);
        machine.data.setInteger("nativeMetaId", base.getMetaTileID());
        machine.data.setTag("controllerItem", item.writeToNBT(new NBTTagCompound()));
    }

    private static void verifyDefinition(Machine machine, Object controller) throws Exception {
        String[][] shape;
        if ("forge-of-gods".equals(machine.spec.id)) {
            shape = (String[][]) Class
                .forName("tectech.thing.metaTileEntity.multi.godforge.structure.ForgeOfGodsStructureString")
                .getField("MAIN_STRUCTURE")
                .get(null);
        } else if ("bec-observation".equals(machine.spec.id)) {
            shape = (String[][]) controller.getClass()
                .getMethod("getDefinition")
                .invoke(controller);
        } else return;
        int width = 0, height = 0, controllers = 0;
        for (int z = 0; z < shape.length; z++) {
            height = Math.max(height, shape[z].length);
            for (int y = 0; y < shape[z].length; y++) {
                width = Math.max(width, shape[z][y].length());
                for (int x = 0; x < shape[z][y].length(); x++) if (shape[z][y].charAt(x) == '~') {
                    controllers++;
                    TestLabTasks.require(
                        x == machine.spec.a && y == machine.spec.b && z == machine.spec.c,
                        "Actual native controller offset differs from reviewed fixture");
                }
            }
        }
        TestLabTasks.require(
            width == machine.spec.width && height == machine.spec.height
                && shape.length == machine.spec.length
                && controllers == 1,
            "Actual native structure dimensions differ from reviewed fixture");
    }

    NBTTagCompound manifest() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList pages = new NBTTagList();
        for (Machine machine : machines) pages.appendTag(machine.data == null ? machine.tag() : machine.data.copy());
        tag.setTag("pages", pages);
        return tag;
    }

    NBTTagCompound pageManifest(String id) throws IOException {
        Machine machine = find(id);
        return machine.data == null ? machine.tag() : (NBTTagCompound) machine.data.copy();
    }

    void useFacility(String id) throws IOException {
        if (!id.endsWith(":source")) throw new IOException("Native machine fixtures expose source-only acceptance");
        Machine machine = find(id.substring(0, id.length() - 7));
        TestLabTasks.require(
            machine.data != null && "READY".equals(machine.data.getString("state")),
            "Build and verify the native machine platform first");
        runtime.useNativeZones(machine.zone, machine.zone);
    }

    boolean owns(TestLabZone zone) {
        for (Machine machine : machines) if (machine.zone == zone) return true;
        return false;
    }

    boolean contains(int x, int y, int z, int sx, int sy, int sz) {
        for (Machine machine : machines) if (machine.zone.contains(x, y, z, sx, sy, sz)) return true;
        return false;
    }

    private Machine find(String id) throws IOException {
        for (Machine machine : machines) if (machine.id.equals(id)) return machine;
        throw new IOException("Unknown configured native machine platform: " + id);
    }

    private BaseMetaTileEntity controller(Machine machine) throws IOException {
        TileEntity tile = runtime.world()
            .getTileEntity(machine.controller[0], machine.controller[1], machine.controller[2]);
        TestLabTasks.require(tile instanceof BaseMetaTileEntity, "Native GT controller tile is missing");
        return (BaseMetaTileEntity) tile;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }

    private static final class Spec {

        final String id, type;
        final int width, height, length, a, b, c, zoneX, zoneY, zoneZ;

        Spec(String id, String type, int width, int height, int length, int a, int b, int c, int zoneX, int zoneY,
            int zoneZ) {
            this.id = id;
            this.type = type;
            this.width = width;
            this.height = height;
            this.length = length;
            this.a = a;
            this.b = b;
            this.c = c;
            this.zoneX = zoneX;
            this.zoneY = zoneY;
            this.zoneZ = zoneZ;
        }
    }

    private static final class Machine {

        final Spec spec;
        final String id;
        final TestLabZone zone;
        final int[] controller, first = new int[3], second = new int[3];
        NBTTagCompound claim, data;

        Machine(Spec spec, int x, int y, int z) {
            this(spec, x, y, z, spec.zoneX / 2, spec.zoneY > 16 ? 32 : 6, 8);
        }

        private Machine(Spec spec, int x, int y, int z, int controllerX, int controllerY, int controllerZ) {
            this.spec = spec;
            id = PREFIX + spec.id;
            zone = new TestLabZone(id, x, y, z, spec.zoneX, spec.zoneY, spec.zoneZ);
            if (y < 0 || (long) y + spec.zoneY > 256)
                throw new IllegalArgumentException("Native machine platform exceeds the actual world height");
            controller = new int[] { x + controllerX, y + controllerY, z + controllerZ };
            java.util.Arrays.fill(first, Integer.MAX_VALUE);
            java.util.Arrays.fill(second, Integer.MIN_VALUE);
            for (int a : new int[] { -spec.a, spec.width - 1 - spec.a })
                for (int b : new int[] { -spec.b, spec.height - 1 - spec.b })
                    for (int c : new int[] { -spec.c, spec.length - 1 - spec.c }) {
                        int[] offset = new int[3];
                        ExtendedFacing.NORTH_NORMAL_NONE.getWorldOffset(new int[] { a, b, c }, offset);
                        for (int i = 0; i < 3; i++) {
                            first[i] = Math.min(first[i], controller[i] + offset[i]);
                            second[i] = Math.max(second[i], controller[i] + offset[i]);
                        }
                    }
            if (!zone.contains(controller[0], controller[1], controller[2], 1, 1, 1) || !zone.contains(
                first[0],
                first[1],
                first[2],
                second[0] - first[0] + 1,
                second[1] - first[1] + 1,
                second[2] - first[2] + 1))
                throw new IllegalArgumentException("Native machine capture must fit its owned platform");
        }

        int volume() {
            return (second[0] - first[0] + 1) * (second[1] - first[1] + 1) * (second[2] - first[2] + 1);
        }

        int[] cell(int index) {
            int width = second[0] - first[0] + 1, length = second[2] - first[2] + 1;
            return new int[] { first[0] + index % width, first[1] + index / (width * length),
                first[2] + index / width % length };
        }

        String layout() {
            return "native-machine-v1:" + spec.type
                + ":"
                + zone.x
                + ","
                + zone.y
                + ","
                + zone.z
                + ":"
                + zone.sizeX
                + ","
                + zone.sizeY
                + ","
                + zone.sizeZ
                + ":north:trigger=1"
                + (controller[0] == zone.x + spec.zoneX / 2 && controller[1] == zone.y + (spec.zoneY > 16 ? 32 : 6)
                    && controller[2] == zone.z + 8 ? ""
                        : ":controller-offset=" + (controller[0] - zone.x) + "," + (controller[1] - zone.y) + ","
                            + (controller[2] - zone.z));
        }

        NBTTagCompound tag() {
            NBTTagCompound out = claim == null ? new NBTTagCompound() : (NBTTagCompound) claim.copy();
            out.setString("id", id);
            out.setString("kind", "native-machine");
            out.setString("state", "CLAIMED");
            out.setString("machineClass", spec.type);
            out.setTag("source", zone.tag());
            out.setIntArray("controller", controller);
            out.setIntArray("first", first);
            out.setIntArray("second", second);
            out.setInteger("selectedVolume", volume());
            out.setBoolean("sourceOnly", true);
            out.setBoolean("constructionAcceptance", false);
            out.setBoolean("nativeHatchesOrFormationInvented", false);
            out.setString(
                "fingerprintSchema",
                "Relative xyz/block/metadata/tile class/GT meta id; excludes runtime NBT");
            return out;
        }
    }
}
