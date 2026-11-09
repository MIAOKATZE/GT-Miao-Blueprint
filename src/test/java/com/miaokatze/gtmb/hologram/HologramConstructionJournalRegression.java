package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

import appeng.api.storage.data.IAEItemStack;

/** Actual forced/atomic files, native NBT and shared production extraction/restore boundaries. */
public final class HologramConstructionJournalRegression {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramConstructionJournalRegression.class, args)) return;
        run();
        System.out.println("HologramConstructionJournalRegression PASS");
        System.exit(0);
    }

    public static void run() {
        Path previous = HologramConstructionJournal.STORAGE_ROOT.get();
        try (AutoCloseable scope = HologramDemolitionDestinationRegression.nativeClassScope()) {
            Path root = Files.createTempDirectory("gtmb-durable-recovery-");
            HologramConstructionJournal.STORAGE_ROOT.set(root);
            HologramRecoveryDeliveryRegression.Fixture f = new HologramRecoveryDeliveryRegression.Fixture();
            Field identity = net.minecraft.entity.Entity.class.getDeclaredField("entityUniqueID");
            identity.setAccessible(true);
            identity.set(f.player, java.util.UUID.randomUUID());
            ItemStack[] original = HologramMaterialAccess.copyInventory(f.player);
            NBTTagCompound journal = HologramConstructionJournal
                .begin(f.player, 1, 64, 1, Blocks.stone, 2, null, original);
            HologramMaterials.Transaction payment = f.context.begin()
                .journal(journal);
            Path file = root.resolve("gtmb-construction-recovery")
                .resolve(f.player.getUniqueID() + ".nbt");
            int[] removed = { 0 };
            IAEItemStack request = (IAEItemStack) java.lang.reflect.Proxy.newProxyInstance(
                IAEItemStack.class.getClassLoader(),
                new Class<?>[] { IAEItemStack.class },
                (proxy, method, arguments) -> {
                    if ("getStackSize".equals(method.getName())) return 7L;
                    if ("writeToNBT".equals(method.getName())) {
                        NBTTagCompound encoded = (NBTTagCompound) arguments[0];
                        new ItemStack(Items.apple, 1).writeToNBT(encoded);
                        encoded.setLong("Cnt", 7);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
            failure(() -> payment.extract("item", request, () -> {
                try {
                    NBTTagCompound disk = HologramSnapshotCodec.decode(Files.readAllBytes(file));
                    require(
                        disk.getCompoundTag("operation")
                            .getLong("requested") == 7,
                        "write-ahead operation reaches disk before native effect");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                removed[0] += 3;
                throw new IllegalStateException("provider removed three then threw");
            }));
            require(removed[0] == 3 && payment.uncertain(), "unknown native result remains unresolved");
            failure(payment::rollbackExternal);
            HologramSession session = session(f);
            HologramConstructionJournal
                .suspend(session, payment, new IllegalStateException("injected extraction failure"));
            require(session.job == 4 && session.stopTick, "unknown result stops this batch");
            HologramConstructionJournal.owner(f.player)
                .removeTag(HologramConstructionJournal.KEY);
            HologramConstructionJournal.reload(f.player);
            NBTTagCompound reloaded = HologramConstructionJournal.owner(f.player)
                .getCompoundTag(HologramConstructionJournal.KEY);
            require(
                reloaded.getCompoundTag("operation")
                    .getLong("requested") == 7,
                "durable file restores newer evidence over stale player data");
            require(
                !HologramWorldTransactions.operate(session, null, 0),
                "persisted recovery blocks next cell before admission/world/payment");
            failure(() -> HologramConstructionJournal.resolve(f.player, reloaded.getString("id"), "rollback"));
            failure(() -> HologramMaterials.Transaction.adjudicateExtraction(reloaded, 8));
            HologramMaterials.Transaction.adjudicateExtraction(reloaded, 3);
            HologramConstructionJournal.persist(f.player, reloaded);
            require(
                reloaded.getCompoundTag("payment")
                    .getTagList("items", 10)
                    .tagCount() == 1,
                "only adjudicated actual extraction enters sole payment ledger");
            failure(() -> HologramMaterials.Transaction.adjudicateExtraction(reloaded, 3));
            failure(() -> HologramConstructionJournal.resolve(f.player, "wrong-receipt", "accept"));
            HologramConstructionJournal.resolve(f.player, reloaded.getString("id"), "accept");
            HologramConstructionJournal.reload(f.player);
            require(
                !HologramConstructionJournal.pending(f.player),
                "durable settlement tombstone prevents stale NBT resurrection");

            NBTTagCompound second = HologramConstructionJournal
                .begin(f.player, 1, 64, 1, Blocks.stone, 2, null, original);
            HologramMaterials.Transaction secondPayment = f.context.begin()
                .journal(second);
            require(
                !HologramWorldTransactions.restoreCell(
                    session,
                    secondPayment,
                    () -> { throw new IllegalStateException("world refused restoration again"); }),
                "second world restoration failure is contained");
            require(
                session.job == 4 && second.hasKey("beforeInventory", 9) && second.hasKey("payment", 10),
                "joint evidence survives world restoration failure");
            HologramConstructionJournal.resolve(f.player, second.getString("id"), "accept");

            // A directory at the destination makes strict publication fail before calling the native provider.
            Files.delete(file);
            Files.createDirectory(file);
            int[] called = { 0 };
            failure(() -> {
                NBTTagCompound blocked = HologramConstructionJournal
                    .begin(f.player, 1, 64, 1, Blocks.stone, 0, null, original);
                f.context.begin()
                    .journal(blocked)
                    .extract("item", request, () -> {
                        called[0]++;
                        return null;
                    });
            });
            require(called[0] == 0, "journal write failure prevents external mutation");
        } catch (Exception error) {
            throw new AssertionError("Durable construction journal", error);
        } finally {
            if (previous == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previous);
        }
    }

    private static HologramSession session(HologramRecoveryDeliveryRegression.Fixture fixture) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        HologramSession session = (HologramSession) ((sun.misc.Unsafe) field.get(null))
            .allocateInstance(HologramSession.class);
        Field player = HologramSession.class.getDeclaredField("player");
        player.setAccessible(true);
        player.set(session, fixture.player);
        session.job = 1;
        return session;
    }

    private static void failure(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Expected fail-closed rejection");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
