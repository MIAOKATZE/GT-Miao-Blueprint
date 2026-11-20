// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import gregtech.api.enums.ItemList;

/** Exercises the optional provider against the actual registered EBF on a client with an active world. */
final class HologramControllerStandardProbe {

    private HologramControllerStandardProbe() {}

    static Map<String, Object> run() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        ModContainer guide = Loader.instance()
            .getIndexedModList()
            .get("guidenh");
        if (guide == null) {
            evidence.put("status", "GUIDENH_ABSENT");
            return evidence;
        }
        evidence.put("guideNH", guide.getVersion());
        if (Minecraft.getMinecraft().theWorld == null) {
            evidence.put("status", "ACTIVE_WORLD_REQUIRED");
            return evidence;
        }
        ItemStack controller = ItemList.Machine_Multi_BlastFurnace.get(1L);
        int id = controller.getItemDamage();
        if (id <= 0) throw new IllegalStateException("The electric blast furnace must have a nonzero MTE ID");
        HologramControllerTemplate.Source source = new HologramControllerTemplate.Source(
            Block.getBlockFromItem(controller.getItem()),
            0,
            id);
        long started = System.nanoTime();
        HologramControllerTemplate template = new HologramControllerTemplate(source);
        long startedPreparing = System.nanoTime();
        HologramState state = null;
        for (int i = 0; i < 16 && state == null; i++) state = template.step(256, 30_000_000L);
        evidence.put("controller", source.guideId());
        evidence.put("buildMilliseconds", (startedPreparing - started) / 1_000_000L);
        if (state != null) evidence.put("cells", state.cells.size());
        if (!template.standard || state == null || state.cells.size() < 20) {
            evidence.put("status", "FAIL");
            evidence.put(
                "reason",
                template.standard ? "The standard EBF model did not resolve at least 20 cells"
                    : template.unavailableReason);
            return evidence;
        }
        int anchorCount = 0;
        for (HologramState.Cell cell : state.cells) {
            if (cell.dx != 0 || cell.dy != 0 || cell.dz != 0) continue;
            if (cell.targetStack == null || cell.targetStack.getItem() != controller.getItem()
                || cell.targetStack.getItemDamage() != id)
                throw new IllegalStateException("The detached origin is not the actual GT controller");
            anchorCount++;
        }
        if (anchorCount != 1) throw new IllegalStateException("The detached controller origin must be unique");
        evidence.put("status", "PASS");
        evidence.put("anchorCount", anchorCount);
        return evidence;
    }

    /** Same lease, save marker and Core policy as the other GTMB native owned-world probes. */
    static final class OwnedWorld {

        private final Minecraft client;
        private final GuiScreen title;
        private final Path evidence, save;
        private final String run;
        private IntegratedServer server;
        private boolean unloaded;
        private Map<String, Object> result;

        OwnedWorld(Minecraft client, GuiScreen title, Path evidence) throws IOException {
            this.client = client;
            this.title = title;
            this.evidence = evidence;
            if (!Boolean.getBoolean("miaotest.host.environment") || client.theWorld != null
                || client.getIntegratedServer() != null)
                throw new IllegalStateException(
                    "The controller probe needs the registered host environment and no preexisting world");
            run = UUID.fromString(System.getProperty("miaotest.host.runId", ""))
                .toString();
            Path game = client.mcDataDir.toPath()
                .toRealPath();
            JsonObject lease = read(game.resolve(".testlab-host/lease.json"));
            if (!run.equals(
                lease.get("runId")
                    .getAsString()))
                throw new IllegalStateException("The controller probe's host lease changed");
            Path saves = game.resolve("saves")
                .toRealPath();
            if (!saves.getParent()
                .equals(game)) throw new IllegalStateException("The save root left the owned client");
            save = saves.resolve("MiaoHost-" + run);
            Files.createDirectory(save);
            JsonObject marker = new JsonObject();
            marker.addProperty("runId", run);
            marker.addProperty("parent", saves.toString());
            Files.write(
                save.resolve(".miaotest-host-owner.json"),
                marker.toString()
                    .getBytes(StandardCharsets.UTF_8));
            System.setProperty("miaotest.host.worldPath", save.toString());
        }

        Path save() {
            return save;
        }

        void launch() {
            client.launchIntegratedServer(
                save.getFileName()
                    .toString(),
                "GTMB standard controller owned regression",
                new WorldSettings(61312138L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
        }

        Map<String, Object> tick() throws IOException {
            JsonObject environment = environment();
            if (result == null) {
                if (client.theWorld == null || client.thePlayer == null
                    || environment == null
                    || !flag(environment, "policyOwnsWorld")) return null;
                server = requireOwned();
                if (!save.equals(
                    java.nio.file.Paths.get(
                        environment.get("ownedSave")
                            .getAsString())
                        .toRealPath()))
                    throw new IllegalStateException("Core policy reported a different save");
                try {
                    result = run();
                } catch (RuntimeException | LinkageError failure) {
                    result = new LinkedHashMap<>();
                    result.put("status", "FAIL");
                    result.put("reason", failure.toString());
                    result.put("stackTrace", HologramTestLabUiProfile.failureStack(failure));
                }
                result.put("ownedSave", save.toString());
                result.put("corePolicyObserved", true);
                close();
                return null;
            }
            if (client.theWorld != null || client.thePlayer != null
                || environment == null
                || !flag(environment, "serverStopped")
                || flag(environment, "registered")) return null;
            result.put("worldStopped", true);
            result.put("titleRestored", client.currentScreen == title);
            result.put("worldDirectoryCleanup", "RUNNER_PENDING");
            return result;
        }

        void close() throws IOException {
            if (unloaded || client.getIntegratedServer() == null && client.theWorld == null) return;
            if (server == null) server = requireOwned();
            if (client.theWorld != null) {
                if (requireOwned() != server) throw new IllegalStateException("The controller probe's world changed");
                client.theWorld.sendQuittingDisconnectingPacket();
                client.loadWorld(null);
            }
            server.initiateShutdown();
            client.displayGuiScreen(title);
            unloaded = true;
        }

        private IntegratedServer requireOwned() throws IOException {
            IntegratedServer current = client.getIntegratedServer();
            WorldServer overworld = current == null ? null : current.worldServerForDimension(0);
            if (overworld == null || !current.isSinglePlayer()
                || !save.equals(
                    overworld.getSaveHandler()
                        .getWorldDirectory()
                        .toPath()
                        .toRealPath()))
                throw new IllegalStateException("The controller probe refuses an unowned integrated save");
            return current;
        }

        private JsonObject environment() throws IOException {
            Path path = evidence.resolve("environment.json");
            if (!Files.isRegularFile(path)) return null;
            try {
                JsonObject value = read(path);
                return value.has("runId") && run.equals(
                    value.get("runId")
                        .getAsString()) ? value : null;
            } catch (com.google.gson.JsonParseException | IllegalStateException publishing) {
                // The environment adapter updates its receipt on the integrated server thread.
                return null;
            }
        }

        private static boolean flag(JsonObject value, String key) {
            return value.has(key) && value.get(key)
                .getAsBoolean();
        }

        private static JsonObject read(Path path) throws IOException {
            return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }
}
