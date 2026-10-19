package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Production admission and private draft lookup, using the existing world fixture that forbids writes. */
final class HologramLibraryServiceRegression {

    @SuppressWarnings("unchecked")
    static void run() {
        Map<UUID, Object> sessions = null;
        UUID ownerId = UUID.fromString("2fa98e10-25fd-4eb6-b33f-23595ace9479");
        Object previous = null;
        EntityPlayerMP owner = null, other = null, reconnect = null;
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            owner = player(unsafe, world, ownerId, "LibraryFixture");
            other = player(unsafe, world, UUID.randomUUID(), "OtherFixture");
            reconnect = player(unsafe, world, ownerId, "LibraryFixture");
            Method authenticated = HologramLibraryService.class
                .getDeclaredMethod("connectedProjector", EntityPlayerMP.class);
            authenticated.setAccessible(true);
            SimpleAssert
                .that((Boolean) authenticated.invoke(null, owner), "connected server projector fixture authenticated");
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
            Constructor<?> ctor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            ctor.setAccessible(true);
            Object session = ctor.newInstance(owner, 0, 64, 0, false, 2);
            set(session, "open", true);
            set(session, "planRevision", 42L);
            Field map = HologramWorkspace.class.getDeclaredField("SESSIONS");
            map.setAccessible(true);
            sessions = (Map<UUID, Object>) map.get(null);
            previous = sessions.put(ownerId, session);
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", (String) get(session, "id"));
            request.setLong("planRevision", 42L);
            // The asynchronous publication task consumes this header on its first budgeted step.
            // Exercise that production boundary without requiring a Forge packet channel in this admission fixture.
            NBTTagCompound header = HologramSnapshots.basicState((HologramSession) session);
            SimpleAssert.that(header.getBoolean("open"), "prepared header carries the one-shot UI open intent");
            SimpleAssert.that(
                !HologramSnapshots.basicState((HologramSession) session)
                    .getBoolean("open"),
                "a second header cannot reopen the same UI intent");
            SimpleAssert.that(!(Boolean) get(session, "open"), "real snapshot consumes one-shot UI open intent");
            SimpleAssert
                .that((Boolean) get(session, "libraryUiActive"), "snapshot retains independent active UI capability");
            SimpleAssert.that(HologramService.librarySessionCurrent(owner, request), "current request admitted");
            owner.inventory.currentItem = 1;
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, request), "switched held tool rejects transfer");
            SimpleAssert
                .that(!(Boolean) authenticated.invoke(null, owner), "held stone cannot authenticate projector upload");
            owner.inventory.currentItem = 0;
            SimpleAssert.that(!HologramService.librarySessionCurrent(other, request), "other UUID cannot activate");
            SimpleAssert.that(
                !HologramService.librarySessionCurrent(reconnect, request),
                "reconnected player object cannot reuse old transfer");
            request.setLong("planRevision", 41L);
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, request), "old plan cannot activate");
            request.setLong("planRevision", 42L);
            String saveReceipt = UUID.randomUUID()
                .toString();
            String savedLocal = "local_" + UUID.randomUUID();
            String jsonSha = HologramBlueprintArtifact.hash(new byte[] { 1, 2, 3 });
            String previewSha = HologramBlueprintArtifact.hash(new byte[] { 4, 5, 6 });
            NBTTagCompound saveRequest = (NBTTagCompound) request.copy();
            saveRequest.setString("saveReceipt", saveReceipt);
            saveRequest.setString("replaceLocalId", savedLocal);
            set(session, "blueprintSaveReceipt", saveReceipt);
            set(session, "blueprintSaveLocalId", savedLocal);
            set(session, "blueprintSaveRevision", 42L);
            set(session, "blueprintDirty", true);
            SimpleAssert.that(
                HologramService.librarySaveCurrent(owner, saveRequest),
                "native artifact save capability is owner, source revision, and local ID bound");
            owner.inventory.currentItem = 1;
            set(session, "libraryUiActive", false);
            SimpleAssert.that(
                HologramLibraryService.saveCurrent(owner, saveRequest)
                    && HologramSessionPolicy.tickValid((HologramSession) session),
                "admitted save continues in the background with its original tool in the original slot");
            ItemStack originalTool = owner.inventory.mainInventory[0];
            owner.inventory.mainInventory[0] = null;
            SimpleAssert.that(
                !HologramLibraryService.saveCurrent(owner, saveRequest),
                "removing original tool revokes the save");
            owner.inventory.mainInventory[0] = originalTool;
            NBTTagCompound changed = (NBTTagCompound) saveRequest.copy();
            changed.setLong("planRevision", 43L);
            SimpleAssert.that(
                !HologramLibraryService.saveCurrent(owner, changed),
                "background save cannot accept a changed plan");
            owner.inventory.currentItem = 0;
            set(session, "libraryUiActive", true);
            SimpleAssert.that(
                !HologramService.librarySaveCurrent(other, saveRequest),
                "other actor cannot submit a saved preview for the owner");
            SimpleAssert.that(
                !HologramService.librarySaveCurrent(reconnect, saveRequest),
                "reconnected actor object cannot reuse preview save capability");
            SimpleAssert.that(
                HologramService.libraryDraftPrepared(owner, saveRequest, jsonSha),
                "server records the exact pre-transfer JSON SHA under its save nonce");
            SimpleAssert.that(
                !HologramService.libraryPreviewVerified(owner, saveRequest, previewSha, previewSha),
                "preview with a different JSON digest cannot verify the save");
            SimpleAssert.that(
                HologramService.libraryPreviewVerified(owner, saveRequest, jsonSha, previewSha),
                "validated source-bound PNG can arm its own preview digest");
            NBTTagCompound acknowledgement = (NBTTagCompound) saveRequest.copy();
            acknowledgement.setString("op", "ackBlueprintSaved");
            acknowledgement.setString("localId", savedLocal);
            acknowledgement.setString("jsonSha256", previewSha);
            acknowledgement.setString("previewSha256", previewSha);
            HologramService.handle(owner, acknowledgement);
            SimpleAssert.that(
                (Boolean) get(session, "blueprintDirty"),
                "ACK of rewritten JSON preserves the dirty source and pending nonce");
            acknowledgement.setString("jsonSha256", jsonSha);
            acknowledgement.setString("previewSha256", jsonSha);
            HologramService.handle(owner, acknowledgement);
            SimpleAssert
                .that((Boolean) get(session, "blueprintDirty"), "ACK of another PNG preserves the dirty source");
            acknowledgement.setString("previewSha256", previewSha);
            acknowledgement.setLong("planRevision", 41L);
            HologramService.handle(owner, acknowledgement);
            SimpleAssert.that(
                (Boolean) get(session, "blueprintDirty"),
                "old source revision cannot acknowledge a complete artifact");
            acknowledgement.setLong("planRevision", 42L);
            owner.inventory.currentItem = 1;
            NBTTagCompound backgroundClose = (NBTTagCompound) request.copy();
            backgroundClose.setString("op", "close");
            HologramService.handle(owner, backgroundClose);
            SimpleAssert.eq(
                saveReceipt,
                get(session, "blueprintSaveReceipt"),
                "closing presentation preserves exact admitted receipt");
            HologramService.handle(owner, acknowledgement);
            SimpleAssert.that(
                !(Boolean) get(session, "blueprintDirty") && ((String) get(session, "blueprintSaveReceipt")).isEmpty(),
                "only exact JSON and verified PNG disk ACK retires the nonce");
            owner.inventory.currentItem = 0;
            set(session, "libraryUiActive", true);
            exerciseLocalReceipt(owner, other, reconnect, session, request, savedLocal, jsonSha, previewSha);
            NBTTagCompound close = (NBTTagCompound) request.copy();
            close.setString("op", "close");
            HologramService.handle(owner, close);
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, request), "closed UI cannot reopen");
            set(session, "open", true);
            HologramSnapshots.basicState((HologramSession) session);
            SimpleAssert.that(
                HologramService.librarySessionCurrent(owner, request),
                "explicit reopen grants fresh UI capability");
            set(session, "capturePending", true);
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, request), "unfinished capture blocks transfer");
            set(session, "capturePending", false);
            set(session, "job", 1);
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, request), "construction blocks activation");
            set(session, "job", 0);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = "private_" + UUID.randomUUID();
            preset.name = "Owner's stage";
            preset.authorName = "Imported creator";
            preset.authorUuid = UUID.randomUUID()
                .toString();
            preset.createdAt = 123;
            preset.controllerType = "area";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
            set(session, "libraryPrivatePreset", preset);
            SimpleAssert
                .that(HologramService.libraryPrivatePreset(owner, preset.id) != null, "owner reads private stage");
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(other, preset.id) == null,
                "private stage cross-owner rejected");
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(reconnect, preset.id) == null,
                "private stage exact player bound");
            owner.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(owner, preset.id) == null,
                "same dimension reloaded world cannot read old private stage");
            owner.worldObj = world;
            HologramPresets.Preset copy = HologramService.libraryPrivatePreset(owner, preset.id);
            copy.name = "Changed copy";
            SimpleAssert.eq("Owner's stage", preset.name, "lookup isolates temporary server draft");
            HologramService.handle(owner, close);
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(owner, preset.id) == null,
                "explicit close revokes private stage lookup after the one-shot open intent was consumed");
            set(session, "open", true);
            HologramSnapshots.basicState((HologramSession) session);
            String originalAuthor = preset.authorUuid;
            HologramLibraryService.stampAuthor(preset, owner, false);
            SimpleAssert.eq(originalAuthor, preset.authorUuid, "update preserves imported creator UUID");
            SimpleAssert.eq(123L, preset.createdAt, "update preserves creation timestamp");
            HologramLibraryService.stampAuthor(preset, owner, true);
            SimpleAssert.eq(ownerId.toString(), preset.authorUuid, "save as stamps actual server actor UUID");
            SimpleAssert.eq("LibraryFixture", preset.authorName, "save as stamps actual profile name");
            SimpleAssert.that(preset.createdAt > 123, "save as establishes new creation time");
            Object replaced = ctor.newInstance(owner, 0, 64, 0, false, 2);
            set(replaced, "open", true);
            set(replaced, "planRevision", 42L);
            sessions.put(ownerId, replaced);
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, request), "new session invalidates old transfer");
            SimpleAssert
                .that(HologramService.libraryPrivatePreset(owner, preset.id) == null, "replacement drops old stage");
            NBTTagCompound bootstrap = new NBTTagCompound();
            bootstrap.setString(
                "quickToken",
                UUID.randomUUID()
                    .toString());
            bootstrap.setInteger("x", 0);
            bootstrap.setInteger("y", 0);
            bootstrap.setInteger("z", 0);
            bootstrap.setInteger("side", 1);
            NBTTagCompound ticket = HologramService.libraryQuickBootstrap(owner, bootstrap);
            Object quickSession = sessions.get(ownerId);
            SimpleAssert.that(!(Boolean) get(quickSession, "open"), "quick bootstrap creates no open UI");
            SimpleAssert
                .that(get(quickSession, "libraryPrivatePreset") == null, "bootstrap never reuses a private stage");
            ticket.setBoolean("quickPlace", true);
            ticket.setString("action", "place");
            SimpleAssert.that(
                HologramService.librarySessionCurrent(owner, ticket),
                "closed bootstrap accepts exact place token");
            ticket.setString("action", "edit");
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, ticket), "quick capability cannot open editor");
            ticket.setString("action", "place");
            String quickToken = ticket.getString("quickToken");
            ticket.setString(
                "quickToken",
                UUID.randomUUID()
                    .toString());
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, ticket), "wrong quick token rejected");
            ticket.setString("quickToken", quickToken);
            owner.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            SimpleAssert.that(
                !HologramService.librarySessionCurrent(owner, ticket),
                "same dimension different world rejects late transfer");
            owner.worldObj = world;
            HologramService.libraryQuickCancel(other, quickToken);
            SimpleAssert.that(
                HologramService.librarySessionCurrent(owner, ticket),
                "another player cannot cancel owner's quick token");
            HologramService.libraryQuickCancel(owner, quickToken);
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, ticket), "Escape revokes pending quick activation");
            SimpleAssert.that(!HologramLibraryFacade.enabled(), "headless/integrated path has no dedicated library");
            SimpleAssert.that(
                !HologramLibraryService.sessionCurrent(owner, request),
                "disabled transfer bridge rejects requests");
            owner.playerNetServerHandler.netManager.channel()
                .close();
            SimpleAssert.that(
                !(Boolean) authenticated.invoke(null, owner),
                "closed actual network channel rejects transfer auth");
            boolean invalid = false;
            try {
                HologramLibraryService.localId("../another-player", false);
            } catch (java.io.IOException expected) {
                invalid = true;
            }
            SimpleAssert.that(invalid, "client private filename cannot traverse directories");
            String publicId = "public_" + UUID.randomUUID();
            SimpleAssert.eq(
                publicId,
                HologramLibraryService.publicId(publicId),
                "public last selection accepts exact opaque public UUID");
            for (String wrongScope : new String[] { preset.id, "local_" + UUID.randomUUID(), "public_../other",
                "public_missing" }) {
                invalid = false;
                try {
                    HologramLibraryService.publicId(wrongScope);
                } catch (java.io.IOException expected) {
                    invalid = true;
                }
                SimpleAssert
                    .that(invalid, "public shortcut cannot read private/local or malformed identifier " + wrongScope);
            }
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Library service regression", failure);
        } finally {
            close(owner);
            close(other);
            close(reconnect);
            if (sessions != null) {
                if (previous == null) sessions.remove(ownerId);
                else sessions.put(ownerId, previous);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void exerciseLocalReceipt(EntityPlayerMP owner, EntityPlayerMP other, EntityPlayerMP reconnect,
        Object session, NBTTagCompound base, String local, String jsonHash, String pngHash)
        throws ReflectiveOperationException {
        Field resultsField = HologramLibraryFacade.class.getDeclaredField("savedResults");
        resultsField.setAccessible(true);
        Map<UUID, Object> results = (Map<UUID, Object>) resultsField.get(null);
        Object previous = results.remove(owner.getUniqueID());
        try {
            NBTTagCompound proof = (NBTTagCompound) base.copy();
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            proof.setString("localId", local);
            proof.setString("jsonSha256", jsonHash);
            proof.setString("previewSha256", "");
            proof.setBoolean("localSaveReceipt", true);
            proof.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            set(session, "blueprintSaveLocalId", local);
            set(session, "blueprintSaveRevision", 42L);
            set(session, "blueprintSaveJsonSha256", jsonHash);
            set(session, "blueprintSavePreviewSha256", "");
            set(session, "blueprintDirty", true);
            Method remember = HologramLibraryFacade.class
                .getDeclaredMethod("rememberSavedResult", EntityPlayerMP.class, NBTTagCompound.class);
            remember.setAccessible(true);
            remember.invoke(null, owner, proof);
            NBTTagCompound ack = (NBTTagCompound) proof.copy();
            ack.setString("op", "ackBlueprintSaved");
            ack.setString("previewSha256", pngHash);
            SimpleAssert.that(!HologramLibraryFacade.localSaveWritten(other, ack), "local receipt is actor bound");
            SimpleAssert
                .that(!HologramLibraryFacade.localSaveWritten(reconnect, ack), "reconnection cannot reuse receipt");
            for (String field : new String[] { "session", "saveReceipt", "localId", "jsonSha256", "previewSha256" }) {
                NBTTagCompound stale = (NBTTagCompound) ack.copy();
                stale.setString(field, "wrong");
                HologramService.handle(owner, stale);
                SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "wrong " + field + " keeps dirty draft");
                SimpleAssert.eq("", get(session, "blueprintSavePreviewSha256"), "rejected receipt cannot bind PNG");
            }
            NBTTagCompound stale = (NBTTagCompound) ack.copy();
            stale.setLong("planRevision", 41L);
            HologramService.handle(owner, stale);
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "old revision cannot commit local receipt");
            // No preview upload has armed a PNG hash: only this exact owner disk receipt binds it.
            HologramService.handle(owner, ack);
            SimpleAssert.that(
                !(Boolean) get(session, "blueprintDirty"),
                "local pair receipt commits through actual dispatcher");
            SimpleAssert.eq(pngHash, get(session, "blueprintSavePreviewSha256"), "owner PNG digest is bound once");
            Object committed = results.get(owner.getUniqueID());
            HologramBusyReplies.rejectLibraryCallback(owner, ack);
            HologramBusyReplies.flush();
            SimpleAssert.that(
                !(Boolean) get(session, "blueprintDirty"),
                "callback congestion cannot dirty an already committed save");
            SimpleAssert
                .eq("saveCommitted", get(committed, "outcome"), "busy callback cannot rewrite committed outcome");
            SimpleAssert
                .eq("saveCommitted", get(committed, "outcome"), "result preserves committed outcome for lost replies");
            NBTTagCompound differentPng = (NBTTagCompound) ack.copy();
            differentPng.setString("previewSha256", jsonHash);
            SimpleAssert.that(
                !HologramLibraryFacade.localSaveWritten(owner, differentPng),
                "terminal receipt cannot rebind PNG");
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            set(session, "blueprintSavePreviewSha256", "");
            set(session, "blueprintDirty", true);
            remember.invoke(null, owner, proof);
            NBTTagCompound canceled = (NBTTagCompound) proof.copy();
            canceled.setString("previewSha256", pngHash);
            HologramLibraryFacade.resolveSavedResult(owner, canceled, true);
            SimpleAssert.eq(
                "saveRejected",
                get(results.get(owner.getUniqueID()), "outcome"),
                "cancel before disk ACK resolves the pre-download result");
            canceled.setString("op", "ackBlueprintSaved");
            HologramService.handle(owner, canceled);
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "late canceled ACK retains unsaved workspace");
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            remember.invoke(null, owner, proof);
            Field queueField = com.miaokatze.gtmb.util.ServerTaskScheduler.class.getDeclaredField("SERVER_TASKS");
            queueField.setAccessible(true);
            java.util.Queue<Runnable> tasks = (java.util.Queue<Runnable>) queueField.get(null);
            java.util.List<Runnable> previousTasks = new java.util.ArrayList<>();
            Runnable task;
            while ((task = tasks.poll()) != null) previousTasks.add(task);
            try {
                HologramLibraryFacade.draftPreparationFailed(owner, proof, "download admission failed");
                SimpleAssert.eq(
                    proof.getString("saveReceipt"),
                    get(session, "blueprintSaveReceipt"),
                    "worker admission failure waits for server-thread source revocation");
                tasks.remove()
                    .run();
                SimpleAssert
                    .eq("", get(session, "blueprintSaveReceipt"), "failed admission revokes exact owner source");
                Method prune = HologramLibraryFacade.class.getDeclaredMethod("pruneSavedResults");
                prune.setAccessible(true);
                prune.invoke(null);
                SimpleAssert.eq(
                    "saveRejected",
                    get(results.get(owner.getUniqueID()), "outcome"),
                    "same-owner prune retires prepublished result instead of leaking pending state");
                SimpleAssert
                    .that((Boolean) get(session, "blueprintDirty"), "admission failure retains workspace edits");
            } finally {
                tasks.clear();
                tasks.addAll(previousTasks);
            }
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            set(session, "blueprintSaveJsonSha256", jsonHash);
            remember.invoke(null, owner, proof);
            NBTTagCompound wrongCallback = (NBTTagCompound) proof.copy();
            wrongCallback.setString("session", "old-session");
            HologramBusyReplies.rejectLibraryCallback(owner, wrongCallback);
            HologramBusyReplies.flush();
            SimpleAssert.eq(
                proof.getString("saveReceipt"),
                get(session, "blueprintSaveReceipt"),
                "rejected stale callback cannot revoke the current save source");
            HologramBusyReplies.rejectLibraryCallback(owner, proof);
            NBTTagCompound listRejection = new NBTTagCompound();
            listRejection.setString("op", "list");
            HologramBusyReplies.reject(owner, listRejection, HologramBusyReplies.Kind.LIBRARY);
            HologramBusyReplies.flush();
            SimpleAssert.eq(
                "",
                get(session, "blueprintSaveReceipt"),
                "exact callback rejection retires its save even when a later busy notice is coalesced");
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "callback rejection retains unsaved workspace");
            Method prune = HologramLibraryFacade.class.getDeclaredMethod("pruneSavedResults");
            prune.setAccessible(true);
            prune.invoke(null);
            SimpleAssert.eq(
                "saveRejected",
                get(results.get(owner.getUniqueID()), "outcome"),
                "rejected callback resolves prepublished save rather than leaving it pending");
        } finally {
            if (previous == null) results.remove(owner.getUniqueID());
            else results.put(owner.getUniqueID(), previous);
        }
    }

    private static EntityPlayerMP player(sun.misc.Unsafe unsafe, HologramServiceEditRegression.ReadOnlyWorld world,
        UUID id, String name) throws ReflectiveOperationException {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        for (Field profile : EntityPlayer.class.getDeclaredFields()) if (profile.getType() == GameProfile.class) {
            profile.setAccessible(true);
            profile.set(player, new GameProfile(id, name));
        }
        // Unsafe skips Entity's UUID initializer; a profile alone does not initialize getUniqueID().
        for (Field identity : Entity.class.getDeclaredFields()) if (identity.getType() == UUID.class) {
            identity.setAccessible(true);
            identity.set(player, id);
        }
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone);
        player.capabilities = new PlayerCapabilities();
        NetworkManager connection = new NetworkManager(false);
        for (Field channel : NetworkManager.class.getDeclaredFields())
            if (channel.getType() == io.netty.channel.Channel.class) {
                channel.setAccessible(true);
                channel.set(connection, new EmbeddedChannel(new ChannelInboundHandlerAdapter()));
            }
        new NetHandlerPlayServer(null, connection, player);
        return player;
    }

    private static void close(EntityPlayerMP player) {
        if (player != null && player.playerNetServerHandler != null) player.playerNetServerHandler.netManager.channel()
            .close();
    }

    private static void set(Object session, String name, Object value) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static Object get(Object session, String name) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }
}
