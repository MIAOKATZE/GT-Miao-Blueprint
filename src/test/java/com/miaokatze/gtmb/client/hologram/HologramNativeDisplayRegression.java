package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.covers.CoverContext;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntityPipe;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEFluidPipe;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.util.GTUtility;
import gregtech.common.covers.Cover;

/** Exercises the real GT pipe overload, hatch callbacks and cover registry without GL or a live tile. */
public final class HologramNativeDisplayRegression {

    private HologramNativeDisplayRegression() {}

    public static void run() {
        try {
            pipeOverload();
            nativePipeClass();
            hatchCasing();
            coverCallbacks();
            pipeGeometry();
            detachedMetadata();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native display regression", failure);
        }
    }

    private static void pipeOverload() {
        final int[] expected = new int[2];
        final int[] calls = { 0 };
        ITexture[][] layers = new ITexture[6][];
        for (int side = 0; side < 6; side++) layers[side] = new ITexture[] { texture(), texture() };
        IMetaTileEntityPipe pipe = (IMetaTileEntityPipe) Proxy.newProxyInstance(
            IMetaTileEntityPipe.class.getClassLoader(),
            new Class<?>[] { IMetaTileEntityPipe.class },
            (proxy, method, args) -> {
                SimpleAssert.eq("getTexture", method.getName(), "only read-only native pipe texture callback is used");
                SimpleAssert
                    .that(args[2] instanceof Integer, "pipe uses connection overload, not error machine overload");
                int side = ((ForgeDirection) args[1]).ordinal();
                SimpleAssert.eq(null, args[0], "no live or fabricated tile reaches pipe texture callback");
                SimpleAssert.eq(expected[0], args[2], "native callback receives normalized texture connections");
                SimpleAssert.eq(7, args[3], "native material dye is retained");
                SimpleAssert.eq(
                    expected[0] == 0 || (expected[0] & 1 << side) != 0,
                    args[4],
                    "native end-face flag follows GT semantics");
                SimpleAssert.eq((expected[1] & 1 << side) != 0, args[5], "redstone flag belongs to the correct side");
                calls[0]++;
                return layers[side];
            });
        for (int mask = 0; mask < 64; mask++) {
            NBTTagCompound display = new NBTTagCompound();
            display.setInteger("connections", mask);
            display.setInteger("color", 7);
            display.setInteger("redstoneMask", mask ^ 63);
            expected[0] = Integer.bitCount(mask) == 1 ? mask | 1 << (Integer.numberOfTrailingZeros(mask) ^ 1) : mask;
            expected[1] = mask ^ 63;
            ITexture[][] actual = HologramMachineTextures.resolve(pipe, 2, new int[] { 0, 1, 2, 3, 4, 5 }, display);
            for (int side = 0; side < 6; side++)
                SimpleAssert.that(actual[side] == layers[side], "native pipe material layers are preserved");
            SimpleAssert
                .eq(mask, display.getInteger("connections"), "texture normalization does not rewrite pipe geometry");
        }
        SimpleAssert.eq(64 * 6, calls[0], "all six pipe sides are queried for all connection masks");
    }

    private static void hatchCasing() throws ReflectiveOperationException {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ProbeHatch hatch = (ProbeHatch) unsafe.allocateInstance(ProbeHatch.class);
        hatch.inactive = new ITexture[] { texture(), texture() };
        hatch.active = new ITexture[] { texture(), texture() };
        ITexture background = texture();
        ITexture[] originalPage = Textures.BlockIcons.casingTexturePages[1];
        try {
            Textures.BlockIcons.casingTexturePages[1] = new ITexture[128];
            Textures.BlockIcons.casingTexturePages[1][19] = background;
            NBTTagCompound display = new NBTTagCompound();
            display.setInteger("texturePage", 1);
            display.setInteger("textureIndex", 19);
            for (int front = 0; front < 6; front++) for (boolean active : new boolean[] { false, true }) {
                display.setBoolean("active", active);
                ITexture[][] actual = HologramMachineTextures
                    .resolve(hatch, front, new int[] { 0, 1, 2, 3, 4, 5 }, display);
                for (int side = 0; side < 6; side++) {
                    if (side == front) SimpleAssert.that(
                        actual[side] == (active ? hatch.active : hatch.inactive),
                        "native hatch front overlays keep real facing and active state");
                    else SimpleAssert
                        .that(actual[side][0] == background, "every other hatch face uses the actual casing texture");
                }
                SimpleAssert.that(hatch.received == background, "hatch native callback receives actual casing");
            }
            Field page = gregtech.api.metatileentity.implementations.MTEHatch.class.getDeclaredField("texturePage");
            page.setAccessible(true);
            SimpleAssert.eq(0, page.getInt(hatch), "read-only resolution never changes the shared prototype casing");
            SimpleAssert.eq(null, hatch.getBaseMetaTileEntity(), "no world tile is created or attached to a hatch");
        } finally {
            Textures.BlockIcons.casingTexturePages[1] = originalPage;
        }
    }

    private static void nativePipeClass() {
        ProbePipe pipe = new ProbePipe();
        SimpleAssert.that(
            !(((Object) pipe) instanceof IMetaTileEntityPipe),
            "real GT native pipe inheritance does not implement the legacy pipe interface");
        for (int mask = 0; mask < 64; mask++) {
            NBTTagCompound display = new NBTTagCompound();
            display.setInteger("connections", mask);
            display.setInteger("color", 7);
            display.setInteger("redstoneMask", mask ^ 63);
            pipe.expectedConnections = Integer.bitCount(mask) == 1
                ? mask | 1 << (Integer.numberOfTrailingZeros(mask) ^ 1)
                : mask;
            pipe.expectedRedstone = mask ^ 63;
            ITexture[][] actual = HologramMachineTextures.resolve(pipe, 2, new int[] { 0, 1, 2, 3, 4, 5 }, display);
            for (int side = 0; side < 6; side++) SimpleAssert.that(
                actual[side] == pipe.layers[side],
                "native MTEFluidPipe class routes to its real connection overload");
        }
        SimpleAssert.eq(384, pipe.pipeCalls, "real native pipe class receives six faces for all 64 masks");
        SimpleAssert.eq(0, pipe.machineCalls, "real native pipe never calls the error machine overload");
        SimpleAssert.eq(null, pipe.getBaseMetaTileEntity(), "unregistered native class probe attaches no base tile");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void coverCallbacks() throws ReflectiveOperationException {
        Field field = CoverRegistry.class.getDeclaredField("covers");
        field.setAccessible(true);
        Map registrations = (Map) field.get(null);
        Map saved = new HashMap(registrations);
        ITexture registered = texture(), overlay = texture(), special = texture();
        try {
            ItemStack item = new ItemStack(Items.paper, 1, 137);
            CoverRegistry.registerCover(item, registered, context -> new ProbeCover(context, overlay, special));
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("id", GTUtility.stackToInt(item));
            row.setByte("s", (byte) 5);
            NBTTagCompound data = new NBTTagCompound();
            data.setInteger("appearance", 42);
            row.setTag("d", data);
            SimpleAssert.that(
                HologramTextures.coverTexture(row, 5, false) == overlay,
                "machine cover chooses native overlay callback");
            SimpleAssert.that(
                HologramTextures.coverTexture(row, 5, true) == special,
                "pipe cover chooses native full-face callback");
            SimpleAssert.eq(
                42,
                row.getCompoundTag("d")
                    .getInteger("appearance"),
                "native cover display leaves recorded NBT unchanged");
            ItemStack fallbackItem = new ItemStack(Items.paper, 1, 138);
            CoverRegistry.registerCover(fallbackItem, registered, context -> new ProbeCover(context, null, null));
            row.setInteger("id", GTUtility.stackToInt(fallbackItem));
            SimpleAssert.that(
                HologramTextures.coverTexture(row, 5, true) == registered,
                "absent native cover override uses the registered cover texture");
        } finally {
            registrations.clear();
            registrations.putAll(saved);
        }
    }

    private static void pipeGeometry() {
        for (int mask = 0; mask < 64; mask++)
            for (HologramGeometry.Surface surface : HologramGeometry.pipe(.5f, mask, 0)) {
                for (double[] vertex : surface.vertices) for (double coordinate : vertex) SimpleAssert
                    .that(coordinate >= -.5 && coordinate <= .5, "pipe geometry remains inside its own cell");
            }
        SimpleAssert.eq(
            6,
            HologramGeometry.pipe(.5f, 0, 0)
                .size(),
            "disconnected pipe renders its native centered body");
        SimpleAssert.eq(
            6,
            HologramGeometry.pipe(1, 63, 63)
                .size(),
            "full-thickness pipe uses the cube rendering branch");
        int connected = 1 << 2;
        for (HologramGeometry.Surface face : HologramGeometry.pipe(.5f, connected, connected)) {
            if (face.coverSide != 2 || face.side != 2) continue;
            double[] min = face.vertices[0], max = face.vertices[6];
            SimpleAssert.that(
                max[0] <= -.25 || min[0] >= .25 || max[1] <= -.25 || min[1] >= .25,
                "native connected cover plate leaves the pipe opening uncovered");
        }
    }

    private static void detachedMetadata() {
        NBTTagCompound source = new NBTTagCompound(), display = new NBTTagCompound();
        display.setInteger("connections", 21);
        source.setTag("actualDisplay", display);
        HologramState.Cell cell = new HologramState.Cell(0, source);
        display.setInteger("connections", 42);
        SimpleAssert
            .eq(21, cell.actualDisplay.getInteger("connections"), "client display state is detached from packet NBT");
        SimpleAssert.that(cell.targetDisplay.hasNoTags(), "legacy snapshots retain empty optional display state");
    }

    private static ITexture texture() {
        return (ITexture) Proxy.newProxyInstance(
            ITexture.class.getClassLoader(),
            new Class<?>[] { ITexture.class },
            (proxy, method, args) -> "isValidTexture".equals(method.getName()) ? true : null);
    }

    public static final class ProbeHatch extends MTEHatchInput {

        ITexture[] inactive, active;
        ITexture received;

        private ProbeHatch() {
            super("native_display_probe", 0, new String[0], new ITexture[0][][]);
        }

        @Override
        public ITexture[] getTexturesInactive(ITexture background) {
            received = background;
            return inactive;
        }

        @Override
        public ITexture[] getTexturesActive(ITexture background) {
            received = background;
            return active;
        }
    }

    private static final class ProbePipe extends MTEFluidPipe {

        final ITexture[][] layers = new ITexture[6][];
        int pipeCalls, machineCalls, expectedConnections, expectedRedstone;

        ProbePipe() {
            // The clone constructor has no registration or world side effects; callback routing needs no material.
            super("native_pipe_display_probe", .375f, null, 1, 1000, false, 1);
            for (int side = 0; side < 6; side++) layers[side] = new ITexture[] { texture(), texture() };
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity base, ForgeDirection side, int connections, int color,
            boolean connected, boolean redstone) {
            pipeCalls++;
            SimpleAssert.eq(null, base, "native pipe display callback has no world tile");
            SimpleAssert.eq(expectedConnections, connections, "native class receives normalized texture mask");
            SimpleAssert.eq(7, color, "native class receives actual pipe color");
            SimpleAssert.eq(
                connections == 0 || (connections & side.flag) != 0,
                connected,
                "native class receives GT's connected-end flag");
            SimpleAssert.eq((expectedRedstone & side.flag) != 0, redstone, "native class receives side redstone");
            return layers[side.ordinal()];
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity base, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            machineCalls++;
            throw new AssertionError("Native fluid pipe was routed to the error machine texture overload");
        }
    }

    private static final class ProbeCover extends Cover {

        private final ITexture overlay, special;

        ProbeCover(CoverContext context, ITexture overlay, ITexture special) {
            super(context, null);
            this.overlay = overlay;
            this.special = special;
        }

        @Override
        protected void readDataFromNbt(NBTBase data) {
            SimpleAssert.eq(
                42,
                ((NBTTagCompound) data).getInteger("appearance"),
                "real cover factory receives recorded appearance NBT");
            SimpleAssert.eq(ForgeDirection.EAST, getSide(), "real cover factory receives recorded side");
            SimpleAssert.eq(null, getTile(), "cover display has no live tile dependency");
        }

        @Override
        public ITexture getOverlayTexture() {
            return overlay;
        }

        @Override
        public ITexture getSpecialFaceTexture() {
            return special;
        }
    }
}
