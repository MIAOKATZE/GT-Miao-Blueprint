package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramAeBlueprint;
import com.miaokatze.gtmb.hologram.HologramAeNativePreview;
import com.miaokatze.gtmb.hologram.HologramNativeCapture;

import appeng.api.AEApi;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.util.AEColor;
import appeng.items.parts.ItemFacade;
import appeng.me.helpers.AENetworkProxy;
import appeng.parts.AEBasePart;
import appeng.parts.CableBusContainer;
import appeng.parts.networking.PartCable;
import appeng.tile.networking.TileCableBus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Genuine source/canonical/current/target/native-control matrix, requiring the registered AE and FMP client. */
public final class HologramAeCablePreviewRegression {

    private static final String[] CABLES = { "cableGlass", "cableCovered", "cableSmart", "cableDense",
        "cableDenseCovered", "lumenCableGlass", "lumenCableCovered", "lumenCableSmart", "lumenCableDense" };
    private static final String[] PARTS = { "importBus", "exportBus", "storageBus", "iface", "terminal",
        "interfaceTerminal", "craftingTerminal", "patternTerminal", "patternTerminalEx", "storageMonitor",
        "conversionMonitor", "levelEmitter", "formationPlane", "annihilationPlane", "p2PTunnelME" };
    private static final java.util.Set<String> UNIMPLEMENTED_FAMILIES = new java.util.HashSet<>();

    private HologramAeCablePreviewRegression() {}

    public static void run() throws Exception {
        Block cable = AEApi.instance()
            .definitions()
            .blocks()
            .multiPart()
            .maybeBlock()
            .get();
        Block multipart = null;
        for (Object candidate : Block.blockRegistry) if (candidate != null && candidate.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) multipart = (Block) candidate;
        check(multipart != null, "registered original FMP block");
        for (Block host : Arrays.asList(cable, multipart)) {
            matrix(host, cable);
            longChain(host, cable);
        }
        System.out
            .println("AE-CABLE-MATRIX current/target/source/canonical/native-control/material/topology/no-proxy PASS");
    }

    /** Real detached factories only: no GL list or UI scene is created for the large topology pressure fixture. */
    private static void longChain(Block host, Block cableBlock) throws Exception {
        final int length = 4096;
        NBTTagCompound payload = new NBTTagCompound();
        definition(payload, colored("cableGlass", AEColor.Red), ForgeDirection.UNKNOWN);
        if (host != cableBlock) {
            NBTTagCompound part = payload;
            part.setString("id", "ae2_cablebus");
            NBTTagList parts = new NBTTagList();
            parts.appendTag(part);
            payload = new NBTTagCompound();
            payload.setTag("parts", parts);
        }
        NBTTagCompound display = new NBTTagCompound();
        display.setTag("nativeTile", payload);
        NBTTagList rows = new NBTTagList();
        for (int x = 0; x < length; x++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", Block.blockRegistry.getNameForObject(host));
            row.setString("wantId", Block.blockRegistry.getNameForObject(host));
            row.setInteger("x", x);
            row.setInteger("y", 64);
            row.setInteger("z", 1024);
            row.setTag("targetDisplay", display.copy());
            rows.appendTag(row);
        }
        NBTTagCompound scene = new NBTTagCompound();
        scene.setTag("cells", rows);
        NBTTagCompound sourceBefore = (NBTTagCompound) scene.copy();
        HologramState state = new HologramState(scene);
        NBTTagCompound snapshotBefore = (NBTTagCompound) state.fullData();
        HologramBlockAccess access = new HologramBlockAccess(state);
        @SuppressWarnings("unchecked")
        java.util.Map<String, TileEntity> tiles = (java.util.Map<String, TileEntity>) get(
            HologramBlockAccess.class,
            access,
            "tiles");
        @SuppressWarnings("unchecked")
        java.util.Set<String> prepared = (java.util.Set<String>) get(
            HologramBlockAccess.class,
            access,
            "topologyPrepared");
        int maxHydratedPerRequest = 0;
        for (int x = 0; x < length; x++) {
            int before = tiles.size();
            TileEntity tile = access.getTileEntity(x, 64, 1024);
            check(tile != null, "long native chain tile " + x + ": " + access.failure(x, 64, 1024));
            int hydrated = tiles.size() - before;
            maxHydratedPerRequest = Math.max(maxHydratedPerRequest, hydrated);
            check(hydrated <= 2, "one topology request hydrates immediate neighbors without traversing the chain");
            CableBusContainer bus = bus(tile);
            check(bus != null && !bus.isInWorld(), "long native chain never joins a world");
            assertEmpty(bus);
            int expected = (x == 0 ? 0 : 16) | (x + 1 == length ? 0 : 32);
            check(
                connectionMask((PartCable) bus.getPart(ForgeDirection.UNKNOWN)) == expected,
                "cached native chain tile prepares exact two/endpoint arms before rendering " + x);
            check(tile == access.getTileEntity(x, 64, 1024), "long native chain cache preserves tile identity");
            check(
                !field(HologramBlockAccess.class, "preparingTopology").getBoolean(access),
                "topology guard restored after every detached request");
        }
        check(tiles.size() == length && prepared.size() == length, "every cached native chain tile was prepared once");
        check(
            sourceBefore.equals(scene) && snapshotBefore.equals(state.fullData()),
            "long native chain source/snapshot unchanged");
        System.out.println(
            "AE-CABLE-LONGCHAIN " + host.getClass()
                .getSimpleName()
                + " cells="
                + length
                + " prepared="
                + prepared.size()
                + " maxHydratedPerRequest="
                + maxHydratedPerRequest
                + " source-immutable/no-proxy/no-worldjoin/bounded-topology PASS");
    }

    private static void matrix(Block host, Block cableBlock) throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        List<Device> devices = new ArrayList<>();
        int base = 0;
        for (String family : CABLES) {
            for (AEColor color : new AEColor[] { AEColor.Red, AEColor.Blue, AEColor.Transparent }) {
                ItemStack cable = colored(family, color);
                if (cable == null) continue; // The feature can be disabled; every enabled official definition is
                                             // tested.
                fixtures.add(fixture(host, cableBlock, cable, null, ForgeDirection.NORTH, null, base, 0, 40));
                fixtures.add(fixture(host, cableBlock, cable, null, ForgeDirection.NORTH, null, base + 1, 0, 16));
                fixtures.add(fixture(host, cableBlock, cable, null, ForgeDirection.NORTH, null, base, 1, 4));
                base += 4;
            }
        }
        // Different colors touch but cannot acquire a planned cable connection.
        fixtures.add(
            fixture(
                host,
                cableBlock,
                colored("cableGlass", AEColor.Red),
                null,
                ForgeDirection.NORTH,
                null,
                base,
                0,
                32));
        fixtures.add(
            fixture(
                host,
                cableBlock,
                colored("cableGlass", AEColor.Blue),
                null,
                ForgeDirection.NORTH,
                null,
                base + 1,
                0,
                16));
        base += 4;
        int index = 0;
        for (String name : PARTS) {
            ItemStack part = part(name);
            if (part == null) continue;
            ForgeDirection direction = ForgeDirection.VALID_DIRECTIONS[index % 6];
            Block material = index % 2 == 0 ? Blocks.stone : Blocks.planks;
            ItemStack facade = ((ItemFacade) AEApi.instance()
                .definitions()
                .items()
                .facade()
                .maybeItem()
                .get()).createFacadeForItem(new ItemStack(material), false);
            check(facade != null, "enabled genuine facade material");
            fixtures.add(
                fixture(host, cableBlock, colored("cableSmart", AEColor.Green), part, direction, facade, base, 0, 0));
            base += 3;
            index++;
        }
        Device drive = device(true, base + 1, AEColor.Red, ForgeDirection.UP);
        devices.add(drive);
        Fixture driveMatch = fixture(
            host,
            cableBlock,
            colored("cableGlass", AEColor.Red),
            null,
            ForgeDirection.NORTH,
            null,
            base,
            0,
            32);
        driveMatch.deviceConnections = 32;
        fixtures.add(driveMatch);
        fixtures.add(
            fixture(
                host,
                cableBlock,
                colored("cableGlass", AEColor.Blue),
                null,
                ForgeDirection.NORTH,
                null,
                base + 2,
                0,
                16));
        base += 4;
        devices.add(device(false, base + 1, AEColor.Green, ForgeDirection.EAST));
        fixtures.add(
            fixture(
                host,
                cableBlock,
                colored("cableGlass", AEColor.Green),
                null,
                ForgeDirection.NORTH,
                null,
                base,
                0,
                32)); // Interface output points WEST, so this target arm is blocked.
        Fixture interfaceMatch = fixture(
            host,
            cableBlock,
            colored("cableGlass", AEColor.Green),
            null,
            ForgeDirection.NORTH,
            null,
            base + 2,
            0,
            16);
        interfaceMatch.deviceConnections = 16;
        fixtures.add(interfaceMatch);
        NBTTagList rows = new NBTTagList();
        for (Fixture fixture : fixtures) rows.appendTag(fixture.row);
        for (Device device : devices) rows.appendTag(device.row);
        NBTTagCompound scene = new NBTTagCompound();
        scene.setTag("cells", rows);
        HologramState state = new HologramState(scene);
        check(
            state.cells.size() == fixtures.size() + devices.size() && fixtures.size() >= 62,
            "rich enabled cable/part/device fixture matrix");
        for (boolean current : new boolean[] { false, true }) {
            HologramBlockRender.begin(state);
            HologramBlockAccess access = new HologramBlockAccess(state, current);
            int vertices = 0;
            for (int i = 0; i < fixtures.size(); i++) {
                Fixture fixture = fixtures.get(i);
                HologramState.Cell cell = state.cells.get(i);
                NBTTagCompound snapshotBefore = (NBTTagCompound) (current ? cell.actualDisplay : cell.targetDisplay)
                    .copy();
                TileEntity detached = access.getTileEntity(cell.x, cell.y, cell.z);
                if (detached != null) reporterPreflight(bus(detached), fixture.name);
                check(
                    HologramBlockRender.ready(cell, current),
                    fixture.name + ": " + HologramBlockRender.failure(cell, current));
                check(!HologramBlockRender.missingTexture(cell, current), fixture.name + " native atlas material");
                List<double[]> preview = HologramBlockRender.attributes(cell, current);
                check(!preview.isEmpty(), fixture.name + " usable original native quads");
                CableBusContainer bus = bus(detached);
                check(bus != null && !bus.isInWorld(), fixture.name + " detached native host never joins world");
                assertEmpty(bus);
                IPart central = bus.getPart(ForgeDirection.UNKNOWN);
                if (fixture.part != null) {
                    IPart part = bus.getPart(fixture.side);
                    check(
                        part != null && part.getClass() == bus(fixture.source).getPart(fixture.side)
                            .getClass(),
                        fixture.name + " original registered part factory/type");
                }
                int expectedConnections = current ? fixture.connections : plannedConnections(fixture, fixtures);
                check(
                    connectionMask((PartCable) central) == expectedConnections,
                    fixture.name + " exact native connection mask");
                TileEntity control = host == cableBlock ? cableBlock.createTileEntity(null, 0)
                    : nativeMultipart(fixture.persistence);
                control.xCoord = cell.x;
                control.yCoord = cell.y;
                control.zCoord = cell.z;
                // Independent control uses the original native persistence reader and original part state.
                if (host == cableBlock) control.readFromNBT((NBTTagCompound) fixture.persistence.copy());
                packet(
                    (PartCable) bus(control).getPart(ForgeDirection.UNKNOWN),
                    expectedConnections,
                    current ? fixture.channels : 0);
                control.setWorldObj(new HologramRenderWorld(access));
                ControlAccess controlAccess = new ControlAccess(access, control, host);
                List<double[]> expected = HologramBlockRender.nativeControl(cell, current, controlAccess);
                check(
                    signature(preview).equals(signature(expected)),
                    fixture.name + " native control positions/UV/RGB equal");
                IIcon cableIcon = cableIcon((PartCable) bus(control).getPart(ForgeDirection.UNKNOWN), controlAccess);
                check(
                    usesIcon(expected, cableIcon) && HologramBlockRender.usesIcon(cell, current, cableIcon),
                    fixture.name + " exact native rendered cable sprite " + cableIcon.getIconName());
                if (fixture.part != null) {
                    IIcon partIcon = fixture.part.getIconIndex();
                    check(
                        usesIcon(expected, partIcon) && HologramBlockRender.usesIcon(cell, current, partIcon),
                        fixture.name + " distinct bus/terminal sprite");
                    IIcon facadeIcon = fixture.material.getIcon(0, 0);
                    check(
                        usesIcon(expected, facadeIcon) && HologramBlockRender.usesIcon(cell, current, facadeIcon),
                        fixture.name + " real facade material sprite");
                }
                check(
                    snapshotBefore.equals(current ? cell.actualDisplay : cell.targetDisplay),
                    fixture.name + " snapshot unchanged");
                check(
                    fixture.savedSource.equals(saved(fixture.source)),
                    fixture.name + " full native source unchanged");
                check(fixture.canonicalBefore.equals(fixture.canonical), fixture.name + " full canonical unchanged");
                vertices += preview.size();
            }
            for (int i = 0; i < devices.size(); i++) {
                Device device = devices.get(i);
                HologramState.Cell cell = state.cells.get(fixtures.size() + i);
                check(
                    HologramBlockRender.ready(cell, current) && !HologramBlockRender.missingTexture(cell, current),
                    "adjacent native device preview ready/material");
                TileEntity tile = access.getTileEntity(cell.x, cell.y, cell.z);
                check(
                    device.source.getClass()
                        .isInstance(tile) && tile instanceof appeng.me.helpers.IGridProxyable,
                    "adjacent visual device retains its reviewed native type and proxy API");
                AENetworkProxy proxy = ((appeng.me.helpers.IGridProxyable) tile).getProxy();
                check(
                    !proxy.isReady() && get(AENetworkProxy.class, proxy, "node") == null
                        && get(AENetworkProxy.class, proxy, "data") == null,
                    "adjacent visual device no ready/node/data");
                TileEntity control = device.source.getClass()
                    .getConstructor()
                    .newInstance();
                control.readFromNBT((NBTTagCompound) device.persistence.copy());
                control.setWorldObj(new HologramRenderWorld(access));
                List<double[]> expected = HologramBlockRender
                    .nativeControl(cell, current, new ControlAccess(access, control, cell.block(current)));
                check(
                    signature(HologramBlockRender.attributes(cell, current)).equals(signature(expected)),
                    "adjacent Drive/Interface original native control equality");
                check(device.persistence.equals(saved(device.source)), "adjacent native device source unchanged");
            }
            System.out.println(
                "AE-CABLE-MATRIX " + host.getClass()
                    .getSimpleName()
                    + " "
                    + (current ? "current" : "target")
                    + " cells="
                    + fixtures.size()
                    + " usableVertices="
                    + vertices
                    + " controls="
                    + (fixtures.size() + devices.size())
                    + " devices="
                    + devices.size()
                    + " facadeCells="
                    + index
                    + " native-control/atlas/connections/empty-inventories/immutable PASS");
        }
    }

    private static Fixture fixture(Block host, Block cableBlock, ItemStack cable, ItemStack part, ForgeDirection side,
        ItemStack facade, int x, int z, int connections) throws Exception {
        NBTTagCompound raw = new NBTTagCompound();
        raw.setInteger("x", x);
        raw.setInteger("y", 64);
        raw.setInteger("z", z);
        definition(raw, cable, ForgeDirection.UNKNOWN);
        if (part != null) {
            definition(raw, part, side);
            NBTTagCompound extra = raw.getCompoundTag("extra:" + side.ordinal());
            if (((IPartItem) part.getItem())
                .createPartFromItemStack(part.copy()) instanceof appeng.parts.reporting.AbstractPartReporting)
                extra.setByte("spin", (byte) (side.ordinal() & 3));
            // Physical Interface storage/terminal viewcells and old grid identity must never enter visual parts.
            NBTTagCompound item = new ItemStack(Blocks.diamond_block, 7).writeToNBT(new NBTTagCompound());
            NBTTagCompound inventory = new NBTTagCompound();
            inventory.setTag("item0", item.copy());
            inventory.setTag("#0", item.copy());
            extra.setTag("inv", inventory);
            extra.setTag("viewCell", inventory.copy());
            if (((IPartItem) part.getItem())
                .createPartFromItemStack(part.copy()) instanceof appeng.helpers.IInterfaceHost)
                extra.setTag("storage", inventory.copy());
            NBTTagCompound identity = new NBTTagCompound();
            identity.setLong("g", 90000001);
            identity.setInteger("p", 987654);
            extra.setTag("part", identity);
        }
        Block material = null;
        if (facade != null) {
            raw.setTag(
                "facade:" + side.getOpposite()
                    .ordinal(),
                facade.writeToNBT(new NBTTagCompound()));
            material = Block.getBlockFromItem(
                ((ItemFacade) facade.getItem()).getTextureItem(facade)
                    .getItem());
        }
        TileEntity source;
        if (host == cableBlock) source = cableBlock.createTileEntity(null, 0);
        else {
            NBTTagCompound multipart = new NBTTagCompound(), partRaw = (NBTTagCompound) raw.copy();
            partRaw.setString("id", "ae2_cablebus");
            NBTTagList partRows = new NBTTagList();
            partRows.appendTag(partRaw);
            multipart.setTag("parts", partRows);
            source = nativeMultipart(multipart);
        }
        source.xCoord = x;
        source.yCoord = 64;
        source.zCoord = z;
        if (host == cableBlock) source.readFromNBT(raw);
        int channels = 0x135724;
        packet((PartCable) bus(source).getPart(ForgeDirection.UNKNOWN), connections, channels);
        NBTTagCompound sourceBefore = saved(source);
        NBTTagCompound captured = HologramNativeCapture.capture(source);
        check(sourceBefore.equals(saved(source)), "native capture does not mutate source/client flags");
        NBTTagCompound payload = captured;
        // Legitimate canonical source uses the actual audited source schema, independent of the poison visual snapshot.
        NBTTagCompound canonicalInput = (NBTTagCompound) payload.copy();
        NBTTagCompound canonicalCable = host == cableBlock ? canonicalInput
            : canonicalInput.getTagList("parts", 10)
                .getCompoundTagAt(0);
        if (part != null) {
            NBTTagCompound extra = canonicalCable.getCompoundTag("extra:" + side.ordinal());
            extra.removeTag("viewCell");
            extra.removeTag("inv");
        }
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(host, 0, canonicalInput);
        if (analysis.blocking) {
            StringBuilder review = new StringBuilder("AE-CABLE-MATRIX BLOCKED host=").append(
                host.getClass()
                    .getSimpleName())
                .append(" part=")
                .append(
                    part == null ? "cable"
                        : ((IPartItem) part.getItem()).createPartFromItemStack(part.copy())
                            .getClass()
                            .getName());
            for (HologramAeBlueprint.Issue issue : analysis.issues) review.append(" [")
                .append(issue.severity)
                .append(":")
                .append(issue.code)
                .append("@")
                .append(issue.path)
                .append("]");
            System.out.println(review.toString());
        }
        check(!analysis.blocking, "enabled original part has reviewed canonical schema: " + part);
        NBTTagCompound canonical = analysis.payload();
        Method encode = Class.forName("com.miaokatze.gtmb.hologram.HologramBlueprintNbt")
            .getDeclaredMethod("encodeCanonical", Block.class, NBTTagCompound.class);
        encode.setAccessible(true);
        Method target = Class.forName("com.miaokatze.gtmb.hologram.HologramDisplaySnapshot")
            .getDeclaredMethod(
                "target",
                Block.class,
                ItemStack.class,
                int.class,
                com.gtnewhorizon.structurelib.structure.IStructureElement.class,
                String.class,
                boolean.class);
        target.setAccessible(true);
        NBTTagCompound targetDisplay = (NBTTagCompound) target
            .invoke(null, host, cable, -1, null, encode.invoke(null, host, canonical), true);
        NBTTagCompound actualDisplay = new NBTTagCompound();
        actualDisplay.setTag("nativeTile", payload);
        check(
            targetDisplay.hasKey("nativeTile", 10),
            "declared native CBC/FMP target display survives ItemMultiPart material");
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", Block.blockRegistry.getNameForObject(host));
        row.setString("wantId", Block.blockRegistry.getNameForObject(host));
        row.setInteger("x", x);
        row.setInteger("y", 64);
        row.setInteger("z", z);
        row.setString("status", "pending");
        row.setTag("actualDisplay", actualDisplay);
        row.setTag("targetDisplay", targetDisplay);
        return new Fixture(
            row,
            source,
            sourceBefore,
            captured,
            canonical,
            cable,
            part,
            side,
            material,
            connections,
            channels);
    }

    private static int plannedConnections(Fixture fixture, List<Fixture> fixtures) throws ReflectiveOperationException {
        int mask = fixture.deviceConnections;
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (fixture.part != null && fixture.side == direction) continue;
            for (Fixture other : fixtures) {
                if (other.row.getInteger("x") != fixture.row.getInteger("x") + direction.offsetX
                    || other.row.getInteger("y") != 64 + direction.offsetY
                    || other.row.getInteger("z") != fixture.row.getInteger("z") + direction.offsetZ) continue;
                if (other.part != null && other.side == direction.getOpposite()) continue;
                if (bus(fixture.source).getColor()
                    .matches(bus(other.source).getColor())) mask |= 1 << direction.ordinal();
            }
        }
        return mask;
    }

    private static Device device(boolean drive, int x, AEColor color, ForgeDirection up) throws Exception {
        Block block = drive ? AEApi.instance()
            .definitions()
            .blocks()
            .drive()
            .maybeBlock()
            .get()
            : AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeBlock()
                .get();
        TileEntity source = drive ? new appeng.tile.storage.TileDrive() : new appeng.tile.misc.TileInterface();
        source.xCoord = x;
        source.yCoord = 64;
        field(appeng.tile.AEBaseTile.class, "forward").set(source, ForgeDirection.SOUTH);
        field(appeng.tile.AEBaseTile.class, "up").set(source, up);
        field(source.getClass(), "paintedColor").set(source, color);
        if (!drive) field(source.getClass(), "pointAt").set(source, up.getOpposite());
        NBTTagCompound persistence = saved(source), canonical = new NBTTagCompound();
        canonical.setString("orientation_forward", "SOUTH");
        canonical.setString("orientation_up", up.name());
        canonical.setByte("paintedColor", (byte) color.ordinal());
        canonical.setInteger(
            "pointAt",
            up.getOpposite()
                .ordinal());
        NBTTagCompound actual = new NBTTagCompound(), target = new NBTTagCompound(), row = new NBTTagCompound();
        actual.setTag("nativeTile", HologramAeNativePreview.capture(source));
        target.setTag("nativeTile", HologramAeNativePreview.target(block, canonical));
        row.setString("id", Block.blockRegistry.getNameForObject(block));
        row.setString("wantId", Block.blockRegistry.getNameForObject(block));
        row.setInteger("x", x);
        row.setInteger("y", 64);
        row.setString("status", "pending");
        row.setTag("actualDisplay", actual);
        row.setTag("targetDisplay", target);
        return new Device(row, source, persistence);
    }

    private static void assertEmpty(CableBusContainer bus) throws ReflectiveOperationException {
        for (ForgeDirection direction : ForgeDirection.values()) {
            IPart part = bus.getPart(direction);
            if (part == null) continue;
            AENetworkProxy proxy = ((AEBasePart) part).getProxy();
            check(
                !proxy.isReady() && get(AENetworkProxy.class, proxy, "node") == null
                    && get(AENetworkProxy.class, proxy, "data") == null,
                "visual part no ready/node/persistent identity");
            if (part instanceof appeng.helpers.IInterfaceHost)
                for (String name : new String[] { "storage", "patterns" }) {
                    net.minecraft.inventory.IInventory inventory = ((appeng.helpers.IInterfaceHost) part)
                        .getInventoryByName(name);
                    if (inventory != null) for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                        check(inventory.getStackInSlot(slot) == null, "interface visual physical inventory empty");
                }
            if (part instanceof appeng.api.implementations.IUpgradeableHost) {
                net.minecraft.inventory.IInventory upgrades = ((appeng.api.implementations.IUpgradeableHost) part)
                    .getInventoryByName("upgrades");
                if (upgrades != null) for (int slot = 0; slot < upgrades.getSizeInventory(); slot++)
                    check(upgrades.getStackInSlot(slot) == null, "visual upgrades empty");
            }
        }
    }

    /** Execute the native reporter's first light read directly, preserving a complete cause before GL catches it. */
    private static void reporterPreflight(CableBusContainer bus, String name) throws ReflectiveOperationException {
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            IPart part = bus.getPart(direction);
            if (!(part instanceof appeng.parts.reporting.AbstractPartReporting)) continue;
            appeng.parts.reporting.AbstractPartReporting reporter = (appeng.parts.reporting.AbstractPartReporting) part;
            TileEntity tile = reporter.getTile();
            check(
                tile != null && tile.getWorldObj() instanceof HologramRenderWorld,
                name + " native reporting host binds the detached snapshot world");
            int x = tile.xCoord + direction.offsetX, y = tile.yCoord + direction.offsetY,
                z = tile.zCoord + direction.offsetZ;
            int expected = tile.getWorldObj()
                .getBlock(x, y, z)
                .getLightOpacity(tile.getWorldObj(), x, y, z);
            check(
                tile.getWorldObj()
                    .getBlockLightOpacity(x, y, z) == expected,
                name + " native neighboring opacity uses the bounded block callback");
            try {
                int light = reporter.getLightLevel();
                check(
                    light >= 0 && light <= 15
                        && field(appeng.parts.reporting.AbstractPartReporting.class, "opacity").getFloat(reporter)
                            == 255 - expected,
                    name + " native reporter initializes its real opacity cache");
            } catch (RuntimeException | LinkageError nativeFailure) {
                if (Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke")) nativeFailure.printStackTrace();
                throw nativeFailure;
            }
            assertEmpty(bus);
        }
    }

    private static CableBusContainer bus(TileEntity tile) throws ReflectiveOperationException {
        if (tile instanceof TileCableBus) return ((TileCableBus) tile).getCableBus();
        Class<?> base = Class.forName("appeng.fmp.CableBusPart");
        Object partHelper = AEApi.instance()
            .partHelper();
        Class<?> registered = (Class<?>) partHelper.getClass()
            .getMethod("getCombinedInstance", String.class)
            .invoke(partHelper, base.getName());
        for (Object part : (List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile))
            // The independent original FMP factory returns AE's exact combined layer class.
            // Pure visual factories use the audited base. Other subclasses are not accepted.
            if ((part.getClass() == base || part.getClass() == registered) && base.isInstance(part)
                && "ae2_cablebus".equals(
                    part.getClass()
                        .getMethod("getType")
                        .invoke(part)))
                return (CableBusContainer) part.getClass()
                    .getMethod("getCableBus")
                    .invoke(part);
        return null;
    }

    /** Original registered FMP loader and original client composite, kept worldless during binding. */
    private static TileEntity nativeMultipart(NBTTagCompound payload) throws ReflectiveOperationException {
        Class<?> registry = Class.forName("codechicken.multipart.MultiPartRegistry");
        Class<?> buffer = Class.forName("scala.collection.mutable.ListBuffer");
        Object parts = buffer.getConstructor()
            .newInstance();
        NBTTagList rows = payload.getTagList("parts", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = (NBTTagCompound) rows.getCompoundTagAt(i)
                .copy();
            Object part = registry.getMethod("loadPart", String.class, NBTTagCompound.class)
                .invoke(null, row.getString("id"), row);
            check(part != null, "native registered FMP control factory");
            part.getClass()
                .getMethod("load", NBTTagCompound.class)
                .invoke(part, row);
            buffer.getMethod("$plus$eq", Object.class)
                .invoke(parts, part);
        }
        Class<?> generator = Class.forName("codechicken.multipart.MultipartGenerator$");
        Class<?> iterable = Class.forName("scala.collection.Iterable");
        TileEntity tile = (TileEntity) generator
            .getMethod("generateCompositeTile", TileEntity.class, iterable, boolean.class)
            .invoke(
                generator.getField("MODULE$")
                    .get(null),
                null,
                parts,
                true);
        tile.readFromNBT((NBTTagCompound) payload.copy());
        Class.forName("codechicken.multipart.TileMultipart")
            .getMethod("loadParts", iterable)
            .invoke(tile, parts);
        CableBusContainer bus = bus(tile);
        check(
            tile.getWorldObj() == null && bus != null && !bus.isInWorld(),
            "native registered FMP control resolves its original cable layer and never joins world");
        return tile;
    }

    private static List<String> signature(List<double[]> vertices) {
        List<String> result = new ArrayList<>();
        for (int first = 0; first + 3 < vertices.size(); first += 4) {
            double[] a = vertices.get(first), b = vertices.get(first + 1), c = vertices.get(first + 2);
            double bx = b[0] - a[0], by = b[1] - a[1], bz = b[2] - a[2];
            double cx = c[0] - a[0], cy = c[1] - a[1], cz = c[2] - a[2];
            if (Math.abs(by * cz - bz * cy) + Math.abs(bz * cx - bx * cz) + Math.abs(bx * cy - by * cx) < 1e-10)
                continue;
            for (int corner = 0; corner < 4; corner++) result.add(Arrays.toString(vertices.get(first + corner)));
        }
        Collections.sort(result);
        return result;
    }

    private static void definition(NBTTagCompound raw, ItemStack stack, ForgeDirection side) {
        check(stack != null, "enabled official cable/part");
        raw.setTag("def:" + side.ordinal(), stack.writeToNBT(new NBTTagCompound()));
        NBTTagCompound extra = new NBTTagCompound();
        ((IPartItem) stack.getItem()).createPartFromItemStack(stack.copy())
            .writeToNBT(extra);
        raw.setTag("extra:" + side.ordinal(), extra);
    }

    private static ItemStack colored(String name, AEColor color) throws ReflectiveOperationException {
        try {
            Object definition = appeng.api.definitions.IParts.class.getMethod(name)
                .invoke(
                    AEApi.instance()
                        .definitions()
                        .parts());
            return ((appeng.api.util.AEColoredItemDefinition) definition).stack(color, 1);
        } catch (java.lang.reflect.InvocationTargetException unavailableDefinition) {
            Throwable cause = unavailableDefinition.getCause();
            if (cause == null || cause.getClass() != appeng.api.exceptions.MissingDefinition.class)
                throw unavailableDefinition;
            if (UNIMPLEMENTED_FAMILIES.add(name)) System.out.println(
                "AE-CABLE-MATRIX SKIP nativeFamily=" + name
                    + " reason=MissingDefinition message="
                    + cause.getMessage()
                        .replace('\n', ' ')
                        .replace('\r', ' '));
            return null;
        }
    }

    private static ItemStack part(String name) throws ReflectiveOperationException {
        Object definition = appeng.api.definitions.IParts.class.getMethod(name)
            .invoke(
                AEApi.instance()
                    .definitions()
                    .parts());
        return ((appeng.api.definitions.IItemDefinition) definition).maybeStack(1)
            .orNull();
    }

    private static int connectionMask(PartCable cable) throws ReflectiveOperationException {
        int result = 0;
        for (Object direction : (Iterable<?>) get(PartCable.class, cable, "connections"))
            result |= 1 << ((ForgeDirection) direction).ordinal();
        return result;
    }

    /** Required center material follows the native renderer's layout, rather than its inventory/breaking sprite. */
    private static IIcon cableIcon(PartCable cable, IBlockAccess world) throws ReflectiveOperationException {
        appeng.api.util.AECableType type = cable.getCableConnectionType();
        int connections = connectionMask(cable);
        boolean straight = Integer.bitCount(connections) == 2
            && ((connections & 3) == 3 || (connections & 12) == 12 || (connections & 48) == 48);
        if (type == appeng.api.util.AECableType.DENSE || type == appeng.api.util.AECableType.DENSE_COVERED) {
            Class<?> owner = type == appeng.api.util.AECableType.DENSE ? appeng.parts.networking.PartDenseCable.class
                : appeng.parts.networking.PartDenseCableCovered.class;
            Method isDense = owner.getDeclaredMethod("isDense", IBlockAccess.class, ForgeDirection.class);
            isDense.setAccessible(true);
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
                if ((connections & (1 << side.ordinal())) != 0 && !(Boolean) isDense.invoke(cable, world, side))
                    straight = false;
            // The native straight dense branch uses the small smart/covered sprite plus offset icons.
            if (straight) return cable.getTexture(cable.getCableColor());
            Method texture = owner.getDeclaredMethod(
                type == appeng.api.util.AECableType.DENSE ? "getDenseTexture" : "getDenseCoveredTexture",
                AEColor.class);
            texture.setAccessible(true);
            return (IIcon) texture.invoke(cable, cable.getCableColor());
        }
        if (type == appeng.api.util.AECableType.SMART) {
            boolean hasBuses = false;
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
                if ((connections & (1 << side.ordinal())) == 0 && cable.getHost()
                    .getPart(side) instanceof appeng.api.networking.IGridHost) hasBuses = true;
            // Detailed smart centers are covered; a smart bus arm is optional when its native length is >= 8.
            if (!straight || hasBuses) return cable.getCoveredTexture(cable.getCableColor());
        } else if (type == appeng.api.util.AECableType.GLASS) {
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                IPart part = cable.getHost()
                    .getPart(side);
                if (part instanceof appeng.api.networking.IGridHost) {
                    appeng.api.util.AECableType connection = ((appeng.api.networking.IGridHost) part)
                        .getCableConnectionType(side.getOpposite());
                    if (connection == appeng.api.util.AECableType.COVERED
                        || connection == appeng.api.util.AECableType.SMART)
                        return cable.getCoveredTexture(cable.getCableColor());
                }
            }
        }
        return cable.getTexture(cable.getCableColor());
    }

    /** Material evidence uses only original nondegenerate native faces, with the same atlas tolerance as previews. */
    private static boolean usesIcon(List<double[]> vertices, IIcon icon) {
        if (icon == null) return false;
        for (int first = 0; first + 3 < vertices.size(); first += 4) {
            double[] a = vertices.get(first), b = vertices.get(first + 1), c = vertices.get(first + 2);
            double bx = b[0] - a[0], by = b[1] - a[1], bz = b[2] - a[2];
            double cx = c[0] - a[0], cy = c[1] - a[1], cz = c[2] - a[2];
            if (Math.abs(by * cz - bz * cy) + Math.abs(bz * cx - bx * cz) + Math.abs(bx * cy - by * cx) < 1e-10)
                continue;
            double u = 0, v = 0;
            for (int corner = 0; corner < 4; corner++) {
                u += vertices.get(first + corner)[3] / 4;
                v += vertices.get(first + corner)[4] / 4;
            }
            if (u > icon.getMinU() + 1e-6 && u < icon.getMaxU() - 1e-6
                && v > icon.getMinV() + 1e-6
                && v < icon.getMaxV() - 1e-6) return true;
        }
        return false;
    }

    private static void packet(PartCable cable, int connections, int channels) throws java.io.IOException {
        ByteBuf bytes = Unpooled.buffer(5, 5);
        try {
            bytes.writeByte(connections);
            bytes.writeInt(channels);
            cable.readFromStream(bytes);
        } finally {
            bytes.release();
        }
    }

    private static NBTTagCompound saved(TileEntity tile) {
        NBTTagCompound result = new NBTTagCompound();
        tile.writeToNBT(result);
        return result;
    }

    private static Object get(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        return field(owner, name).get(target);
    }

    private static Field field(Class<?> owner, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try {
                Field result = owner.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(owner.getName() + Arrays.toString(names));
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError("AE cable matrix: " + message);
    }

    private static final class Fixture {

        final NBTTagCompound row, savedSource, persistence, canonical, canonicalBefore;
        final TileEntity source;
        final ItemStack part;
        final ForgeDirection side;
        final Block material;
        final int connections, channels;
        int deviceConnections;
        final String name;

        Fixture(NBTTagCompound row, TileEntity source, NBTTagCompound savedSource, NBTTagCompound persistence,
            NBTTagCompound canonical, ItemStack cable, ItemStack part, ForgeDirection side, Block material,
            int connections, int channels) {
            this.row = row;
            this.source = source;
            this.savedSource = savedSource;
            this.persistence = persistence;
            this.canonical = canonical;
            this.canonicalBefore = (NBTTagCompound) canonical.copy();
            this.part = part;
            this.side = side;
            this.material = material;
            this.connections = connections;
            this.channels = channels;
            name = row.getString("id") + "/"
                + cable.getItemDamage()
                + "/"
                + (part == null ? "cable" : part.getItemDamage() + "@" + side.name())
                + "/"
                + row.getInteger("x")
                + ":"
                + row.getInteger("z");
        }
    }

    private static final class Device {

        final NBTTagCompound row, persistence;
        final TileEntity source;

        Device(NBTTagCompound row, TileEntity source, NBTTagCompound persistence) {
            this.row = row;
            this.source = source;
            this.persistence = persistence;
        }
    }

    private static final class ControlAccess implements IBlockAccess {

        private final IBlockAccess neighbors;
        private final TileEntity tile;
        private final Block block;

        ControlAccess(IBlockAccess neighbors, TileEntity tile, Block block) {
            this.neighbors = neighbors;
            this.tile = tile;
            this.block = block;
        }

        private boolean here(int x, int y, int z) {
            return x == tile.xCoord && y == tile.yCoord && z == tile.zCoord;
        }

        public Block getBlock(int x, int y, int z) {
            return here(x, y, z) ? block : neighbors.getBlock(x, y, z);
        }

        public TileEntity getTileEntity(int x, int y, int z) {
            return here(x, y, z) ? tile : neighbors.getTileEntity(x, y, z);
        }

        public int getBlockMetadata(int x, int y, int z) {
            return neighbors.getBlockMetadata(x, y, z);
        }

        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int min) {
            return 0xf000f0;
        }

        public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
            return 0;
        }

        public boolean isAirBlock(int x, int y, int z) {
            return getBlock(x, y, z) == Blocks.air;
        }

        public BiomeGenBase getBiomeGenForCoords(int x, int z) {
            return BiomeGenBase.plains;
        }

        public int getHeight() {
            return 256;
        }

        public boolean extendedLevelsInChunkCache() {
            return false;
        }

        public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
            return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
        }
    }
}
