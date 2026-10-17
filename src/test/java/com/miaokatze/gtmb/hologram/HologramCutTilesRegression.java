package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Restoration ordering only; real AE data integrity is exercised separately by the opt-in native probe. */
public final class HologramCutTilesRegression {

    private static final String ID = "gtmb_test_world_before_read";

    private HologramCutTilesRegression() {}

    @SuppressWarnings("unchecked")
    public static void run() {
        try {
            eventAccess("HologramCutAeDataNativeSmoke");
            eventAccess("HologramCutNativeSmoke");
            eventAccess("HologramCutNativeServerProbe");
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            WorldServer world = (WorldServer) ((sun.misc.Unsafe) singleton.get(null))
                .allocateInstance(WorldServer.class);
            Map<String, Class<?>> types = (Map<String, Class<?>>) ReflectionHelper
                .findField(TileEntity.class, "nameToClassMap", "field_145855_i")
                .get(null);
            Class<?> previous = types.put(ID, WorldReader.class);
            try {
                NBTTagCompound payload = new NBTTagCompound();
                payload.setString("id", ID);
                payload.setInteger("x", -31);
                payload.setInteger("y", 200);
                payload.setInteger("z", 49);
                NBTTagCompound nested = new NBTTagCompound();
                nested.setString("arbitraryOwner", "keep-unrecognized-recipe-and-item-data");
                nested.setIntArray("numbers", new int[] { 7, 31, 513 });
                payload.setTag("unknownNativePayload", nested);
                NBTTagCompound untouched = (NBTTagCompound) payload.copy();
                Factory block = new Factory();
                WorldReader reader = (WorldReader) HologramCutTiles.create(world, block, 11, payload);
                SimpleAssert.that(reader.getWorldObj() == world, "native reader already has the target world");
                SimpleAssert.eq(11, block.metadata, "actual block metadata reaches native factory");
                SimpleAssert.that(reader.sawCoordinates, "native reader sees relocated coordinates before reading");
                SimpleAssert.that(untouched.equals(payload), "native reader cannot mutate the raw entry input");
                SimpleAssert.that(
                    untouched.getCompoundTag("unknownNativePayload")
                        .equals(reader.received),
                    "unrecognized nested payload reaches native reader unchanged");
                WorldReader fallback = (WorldReader) HologramCutTiles.create(world, Blocks.stone, 0, payload);
                SimpleAssert
                    .that(fallback.sawCoordinates, "registered default factory fallback sets world before read");
                WorldReader registered = (WorldReader) HologramCutTiles.create(world, Blocks.chest, 0, payload);
                SimpleAssert.that(
                    registered.sawCoordinates,
                    "native registry class remains authoritative when block factory returns another class");
                payload.setString("id", ID + "_unregistered");
                boolean rejected = false;
                try {
                    HologramCutTiles.create(world, block, 0, payload);
                } catch (IOException expected) {
                    rejected = true;
                }
                SimpleAssert.that(rejected, "missing registered native tile retains the transaction");
            } finally {
                if (previous == null) types.remove(ID);
                else types.put(ID, previous);
            }
        } catch (Exception failure) {
            throw new AssertionError("cut native tile restoration ordering", failure);
        }
    }

    /** Forge generates callers in another package and loader; public methods also need a public owner class. */
    private static void eventAccess(String simpleName) throws IOException {
        String name = "com/miaokatze/gtmb/hologram/" + simpleName;
        try (InputStream input = HologramCutTilesRegression.class.getClassLoader()
            .getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("missing event class resource " + name);
            ClassReader reader = new ClassReader(input);
            int ownerAccess = reader.getAccess();
            int[] handlers = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM5) {

                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                    String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM5) {

                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (!"Lcpw/mods/fml/common/eventhandler/SubscribeEvent;".equals(annotation)) return null;
                            handlers[0]++;
                            SimpleAssert.that(
                                (ownerAccess & Opcodes.ACC_PUBLIC) != 0,
                                "Forge generated event caller requires public owner " + name);
                            SimpleAssert.that(
                                (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT))
                                    == Opcodes.ACC_PUBLIC,
                                "Forge event handler is a public concrete instance method " + name + "." + method);
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            SimpleAssert.that(handlers[0] > 0, "actual SubscribeEvent method inspected " + name);
        }
    }

    public static final class WorldReader extends TileEntity {

        boolean sawCoordinates;
        NBTTagCompound received;

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            if (getWorldObj() == null) throw new IllegalStateException("world required while reading recipe");
            sawCoordinates = xCoord == -31 && yCoord == 200 && zCoord == 49;
            received = (NBTTagCompound) tag.getCompoundTag("unknownNativePayload")
                .copy();
            tag.getCompoundTag("unknownNativePayload")
                .removeTag("arbitraryOwner");
            super.readFromNBT(tag);
        }
    }

    private static final class Factory extends BlockContainer {

        int metadata;

        Factory() {
            super(Material.rock);
        }

        @Override
        public TileEntity createNewTileEntity(World world, int meta) {
            metadata = meta;
            return new WorldReader();
        }
    }
}
