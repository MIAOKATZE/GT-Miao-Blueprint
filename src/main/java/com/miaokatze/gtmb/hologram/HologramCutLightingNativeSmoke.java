package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

/** Same-world native light observer, invoked only by the explicitly owned AE cut fixture. */
final class HologramCutLightingNativeSmoke {

    private static final int REFERENCE_X = 1008, TARGET_X = 1072, Z = 1008, Y = 224, SIDE = 17;
    private static final int AIR_REFERENCE_X = 1216, AIR_PASTE_X = 1152, AIR_CLEAR_X = 1184;
    private static final int EXISTING_REFERENCE_X = 1280, EXISTING_TARGET_X = 1344;
    private static final int CLEAR_REFERENCE_X = 1408, CLEAR_TARGET_X = 1472;
    private static final int[] SAMPLE_Y = { -192, -128, -64, -32, -16, -2, -1, 0, 1 };
    private static final String KEY = "nativeCutLighting";

    private HologramCutLightingNativeSmoke() {}

    static void prepare(WorldServer world, NBTTagCompound oracle) throws Exception {
        require(!world.provider.hasNoSky, "skylight reference requires an ordinary overworld");
        NBTTagCompound data = new NBTTagCompound();
        oracle.setTag(KEY, data);
        boolean airMatches = prepareAirOnly(world, data);
        // A 17-block roof crosses both chunk boundaries. All compared positions share identical local coordinates.
        // Lighting's native radius check must succeed even though this fixture has no connected player.
        for (int origin : new int[] { REFERENCE_X, TARGET_X }) {
            for (int cx = (origin - 18) >> 4; cx <= (origin + SIDE + 18) >> 4; cx++)
                for (int cz = (Z - 18) >> 4; cz <= (Z + SIDE + 18) >> 4; cz++) load(world, cx, cz);
            for (int dx = -1; dx <= SIDE; dx++) for (int dz = -1; dz <= SIDE; dz++) for (int dy : SAMPLE_Y)
                require(world.isAirBlock(origin + dx, Y + dy, Z + dz), "fresh lighting fixture");
        }
        for (int dx = 0; dx < SIDE; dx++) for (int dz = 0; dz < SIDE; dz++) require(
            world.setBlock(REFERENCE_X + dx, Y, Z + dz, dx == 1 && dz == 1 ? Blocks.glowstone : Blocks.stone, 0, 2),
            "ordinary reference placement");
        require(
            world.getBlock(REFERENCE_X, Y, Z) == Blocks.stone
                && world.getBlock(REFERENCE_X + 1, Y, Z + 1) == Blocks.glowstone,
            "native reference blocks actually exist");
        // Establish the ordinary native reference before the production paste. Only the reference receives
        // explicit chunk maintenance; this observer must never repair the target it is meant to measure.
        for (int cx = REFERENCE_X >> 4; cx <= (REFERENCE_X + SIDE - 1) >> 4; cx++)
            for (int cz = Z >> 4; cz <= (Z + SIDE - 1) >> 4; cz++) load(world, cx, cz).func_150804_b(false);
        NBTTagCompound entry = HologramCutEntry.capture(world, REFERENCE_X, Y, Z, SIDE, 1, SIDE);
        data.setTag("entry", entry);
        data.setBoolean("referenceOnlyNativeMaintenance", true);
        data.setBoolean("targetMaintenanceOrRelight", false);
        data.setBoolean(
            "distinctRealRoofChunks",
            load(world, REFERENCE_X >> 4, Z >> 4) != load(world, TARGET_X >> 4, Z >> 4));
        require(data.getBoolean("distinctRealRoofChunks"), "distinct native roof chunks");
        HologramCutWorld.apply(world, entry, TARGET_X, Y, Z, false);
        boolean roofMatches = observe(world, oracle, "immediate", false);
        require(
            airMatches && roofMatches,
            "immediate native comparison failed; air=" + data.getCompoundTag("airOnly")
                + " roof="
                + data.getCompoundTag("immediate"));
        prepareExistingRoof(world, data);
        prepareLastBlockClear(world, data);
        int[] referenceBeforeSave = sampleLights(world, REFERENCE_X), targetBeforeSave = sampleLights(world, TARGET_X);
        data.setIntArray("referenceBeforeSave", referenceBeforeSave);
        data.setIntArray("rawBeforeSave", targetBeforeSave);
        HologramCutWorld.saveAndVerify(world, entry, REFERENCE_X, Y, Z, false);
        HologramCutWorld.saveAndVerify(world, entry, TARGET_X, Y, Z, false);
        int[] referenceAfterSave = sampleLights(world, REFERENCE_X), targetAfterSave = sampleLights(world, TARGET_X);
        data.setIntArray("referenceAfterSave", referenceAfterSave);
        data.setIntArray("rawAfterSave", targetAfterSave);
        boolean readerUnchanged = java.util.Arrays.equals(referenceBeforeSave, referenceAfterSave)
            && java.util.Arrays.equals(targetBeforeSave, targetAfterSave);
        data.setBoolean("saveAndVerifyPreservedLiveLight", readerUnchanged);
        observe(world, oracle, "after-save", true);
        System.out.println("[GTMB-CUT-LIGHTING] save-and-verify live light unchanged=" + readerUnchanged);
        require(readerUnchanged, "native disk verifier changed live light samples");
    }

    static void verify(WorldServer world, NBTTagCompound oracle, String stage) {
        require(oracle.hasKey(KEY, 10), "lighting fixture oracle is present");
        verifyEdgeCases(world, oracle.getCompoundTag(KEY), stage);
        require(
            load(world, AIR_PASTE_X >> 4, Z >> 4).getBlockStorageArray()[Y >> 4] == null
                && load(world, AIR_CLEAR_X >> 4, Z >> 4).getBlockStorageArray()[Y >> 4] == null,
            stage + " air-only paste/clear did not allocate sections");
        observe(world, oracle, stage, true);
        if ("restart-live".equals(stage)) {
            NBTTagCompound data = oracle.getCompoundTag(KEY);
            require(data.getBoolean("referenceOnlyNativeMaintenance"), "native reference was established");
            require(!data.getBoolean("targetMaintenanceOrRelight"), "observer did not repair the raw target");
        }
    }

    private static boolean prepareAirOnly(WorldServer world, NBTTagCompound data) throws Exception {
        NBTTagCompound report = new NBTTagCompound();
        data.setTag("airOnly", report);
        // Ordinary chunk generation/light population is allowed to allocate empty light storage. Establish a
        // deliberate null input after all neighbors load, using only verified air in this explicitly owned fixture.
        for (int origin : new int[] { AIR_REFERENCE_X, AIR_PASTE_X, AIR_CLEAR_X })
            for (int cx = (origin - 18) >> 4; cx <= (origin + 18) >> 4; cx++)
                for (int cz = (Z - 18) >> 4; cz <= (Z + 18) >> 4; cz++) load(world, cx, cz);
        boolean available = true;
        for (int origin : new int[] { AIR_REFERENCE_X, AIR_PASTE_X, AIR_CLEAR_X }) {
            Chunk chunk = load(world, origin >> 4, Z >> 4);
            boolean empty = true;
            for (int x = 0; x < 16; x++) for (int y = Y; y < Y + 16; y++)
                for (int z = 0; z < 16; z++) if (chunk.getBlock(x, y, z) != Blocks.air) empty = false;
            NBTTagCompound preparation = new NBTTagCompound();
            preparation.setBoolean("all4096CellsAir", empty);
            preparation.setBoolean("sectionPreviouslyPresent", chunk.getBlockStorageArray()[Y >> 4] != null);
            if (empty) {
                chunk.getBlockStorageArray()[Y >> 4] = null;
                chunk.setChunkModified();
                preparation.setBoolean("explicitFixtureNullPreparation", true);
            }
            available &= empty;
            report.setTag("prepared" + origin, preparation);
        }
        report.setBoolean("scopeAvailable", available);
        if (!available) {
            report.setString("unavailableReason", "A fixed fixture section contains non-air blocks");
            System.out.println("[GTMB-CUT-LIGHTING] air-only scope unavailable " + report);
            return false;
        }
        Chunk reference = load(world, AIR_REFERENCE_X >> 4, Z >> 4);
        Chunk pasteChunk = load(world, AIR_PASTE_X >> 4, Z >> 4), clearChunk = load(world, AIR_CLEAR_X >> 4, Z >> 4);
        report.setBoolean(
            "distinctRealAirChunks",
            reference != pasteChunk && reference != clearChunk && pasteChunk != clearChunk);
        require(report.getBoolean("distinctRealAirChunks"), "distinct native air chunks");
        int[] nativeBefore = nearbyLight(world, AIR_REFERENCE_X);
        boolean nativeChanged = world.setBlock(AIR_REFERENCE_X, Y, Z, Blocks.air, 0, 2);
        boolean nativeMatches = !nativeChanged && reference.getBlockStorageArray()[Y >> 4] == null
            && java.util.Arrays.equals(nativeBefore, nearbyLight(world, AIR_REFERENCE_X));
        report.setBoolean("nativeNoChange", nativeMatches);
        NBTTagCompound entry = HologramCutEntry.capture(world, AIR_REFERENCE_X, Y, Z, 1, 1, 1);
        boolean matches = nativeMatches;
        for (int target : new int[] { AIR_PASTE_X, AIR_CLEAR_X }) {
            Chunk chunk = load(world, target >> 4, Z >> 4);
            boolean initiallyNull = chunk.getBlockStorageArray()[Y >> 4] == null;
            int[] before = nearbyLight(world, target);
            HologramCutWorld.apply(world, entry, target, Y, Z, target == AIR_CLEAR_X);
            int[] after = nearbyLight(world, target);
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("initialSectionNull", initiallyNull);
            result.setBoolean("finalSectionNull", chunk.getBlockStorageArray()[Y >> 4] == null);
            result.setBoolean("lightUnchanged", java.util.Arrays.equals(before, after));
            result.setIntArray("lightBefore", before);
            result.setIntArray("lightAfter", after);
            report.setTag(target == AIR_CLEAR_X ? "clear" : "paste", result);
            matches &= initiallyNull && result.getBoolean("finalSectionNull") && result.getBoolean("lightUnchanged");
        }
        report.setBoolean("matchesNative", matches);
        data.setTag("airOnly", report);
        System.out.println("[GTMB-CUT-LIGHTING] air-only " + report);
        return matches;
    }

    private static int[] nearbyLight(WorldServer world, int x) {
        int[] samples = new int[54];
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int[] light = light(world, x + dx, Y + dy, Z + dz);
            samples[index++] = light[0];
            samples[index++] = light[1];
        }
        return samples;
    }

    private static int[] sampleLights(WorldServer world, int origin) {
        int[] samples = new int[(SIDE + 2) * (SIDE + 2) * SAMPLE_Y.length * 2];
        int index = 0;
        for (int dx = -1; dx <= SIDE; dx++) for (int dz = -1; dz <= SIDE; dz++) for (int dy : SAMPLE_Y) {
            int[] light = light(world, origin + dx, Y + dy, Z + dz);
            samples[index++] = light[0];
            samples[index++] = light[1];
        }
        return samples;
    }

    private static void prepareExistingRoof(WorldServer world, NBTTagCompound data) throws Exception {
        for (int origin : new int[] { EXISTING_REFERENCE_X, EXISTING_TARGET_X }) {
            loadBox(world, origin);
            for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                require(world.isAirBlock(origin + x, Y + 16, Z + z), "fresh existing-roof fixture");
                require(world.setBlock(origin + x, Y + 16, Z + z, Blocks.stone, 0, 2), "native existing roof");
            }
        }
        require(world.setBlock(EXISTING_REFERENCE_X, Y, Z, Blocks.stone, 0, 2), "native lower reference block");
        // The reference gets native lighting preparation. The target gets a verified null air section beneath
        // its already present roof, then only the production apply creates and lights the new section.
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) world.markBlocksDirtyVertical(EXISTING_REFERENCE_X + x, Z + z, Y, Y + 15);
        Chunk target = load(world, EXISTING_TARGET_X >> 4, Z >> 4);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = Y; y < Y + 16; y++)
            require(target.getBlock(x, y, z) == Blocks.air, "existing-roof target section is all air");
        target.getBlockStorageArray()[Y >> 4] = null;
        target.setChunkModified();
        int height = target.getHeightValue(0, 0);
        NBTTagCompound entry = HologramCutEntry.capture(world, EXISTING_REFERENCE_X, Y, Z, 1, 1, 1);
        HologramCutWorld.apply(world, entry, EXISTING_TARGET_X, Y, Z, false);
        int[] expected = sectionLight(world, EXISTING_REFERENCE_X), actual = sectionLight(world, EXISTING_TARGET_X);
        NBTTagCompound report = new NBTTagCompound();
        report.setInteger("heightBefore", height);
        report.setInteger("heightAfter", target.getHeightValue(0, 0));
        report.setInteger("lightMismatches", differences(expected, actual));
        report.setIntArray("referenceShadow", light(world, EXISTING_REFERENCE_X + 1, Y + 8, Z + 1));
        report.setIntArray("rawShadow", light(world, EXISTING_TARGET_X + 1, Y + 8, Z + 1));
        report.setBoolean("explicitTargetAirSectionPreparation", true);
        data.setTag("existingRoofNewSection", report);
        data.setIntArray("existingRoofExpectedLight", expected);
        System.out.println("[GTMB-CUT-LIGHTING] existing-roof-new-section " + report);
        require(height == Y + 17 && target.getHeightValue(0, 0) == height, "existing roof height unchanged");
        require(report.getIntArray("referenceShadow")[0] > 0, "native existing-roof shadow has lateral skylight");
        require(
            java.util.Arrays.equals(expected, actual),
            "new section below unchanged roof differs from native light");
        HologramCutWorld.saveAndVerify(world, entry, EXISTING_REFERENCE_X, Y, Z, false);
        HologramCutWorld.saveAndVerify(world, entry, EXISTING_TARGET_X, Y, Z, false);
        require(
            java.util.Arrays.equals(expected, sectionLight(world, EXISTING_REFERENCE_X))
                && java.util.Arrays.equals(actual, sectionLight(world, EXISTING_TARGET_X)),
            "existing-roof save verifier preserved live light");
    }

    private static void prepareLastBlockClear(WorldServer world, NBTTagCompound data) throws Exception {
        loadBox(world, CLEAR_REFERENCE_X);
        loadBox(world, CLEAR_TARGET_X);
        require(world.setBlock(CLEAR_REFERENCE_X, Y, Z, Blocks.glowstone, 0, 2), "native last-block reference");
        NBTTagCompound entry = HologramCutEntry.capture(world, CLEAR_REFERENCE_X, Y, Z, 1, 1, 1);
        HologramCutWorld.apply(world, entry, CLEAR_TARGET_X, Y, Z, false);
        require(world.setBlock(CLEAR_REFERENCE_X, Y, Z, Blocks.air, 0, 2), "native last-block clear");
        HologramCutWorld.apply(world, entry, CLEAR_TARGET_X, Y, Z, true);
        int[] expected = nearbyLight(world, CLEAR_REFERENCE_X), actual = nearbyLight(world, CLEAR_TARGET_X);
        NBTTagCompound report = new NBTTagCompound();
        report.setInteger("nativeHeight", world.getHeightValue(CLEAR_REFERENCE_X, Z));
        report.setInteger("rawHeight", world.getHeightValue(CLEAR_TARGET_X, Z));
        report.setInteger("nativeHeightMinimum", load(world, CLEAR_REFERENCE_X >> 4, Z >> 4).heightMapMinimum);
        report.setInteger("rawHeightMinimum", load(world, CLEAR_TARGET_X >> 4, Z >> 4).heightMapMinimum);
        report.setInteger("lightMismatches", differences(expected, actual));
        data.setTag("lastBlockClear", report);
        data.setIntArray("lastBlockClearExpectedLight", expected);
        System.out.println("[GTMB-CUT-LIGHTING] last-block-clear " + report);
        require(
            report.getInteger("nativeHeight") == 0 && report.getInteger("rawHeight") == 0,
            "last block height resets to zero");
        require(
            report.getInteger("nativeHeightMinimum") == 0 && report.getInteger("rawHeightMinimum") == 0,
            "last block minimum height cache resets to zero");
        require(java.util.Arrays.equals(expected, actual), "last-block clear light differs from native");
        HologramCutWorld.saveAndVerify(world, entry, CLEAR_REFERENCE_X, Y, Z, true);
        HologramCutWorld.saveAndVerify(world, entry, CLEAR_TARGET_X, Y, Z, true);
    }

    private static void verifyEdgeCases(WorldServer world, NBTTagCompound data, String stage) {
        int[] roof = data.getIntArray("existingRoofExpectedLight"),
            clear = data.getIntArray("lastBlockClearExpectedLight");
        require(roof.length == 8192 && clear.length == 54, stage + " actual edge-case oracles");
        require(
            java.util.Arrays.equals(roof, sectionLight(world, EXISTING_REFERENCE_X))
                && java.util.Arrays.equals(roof, sectionLight(world, EXISTING_TARGET_X)),
            stage + " existing-roof new-section light");
        require(
            java.util.Arrays.equals(clear, nearbyLight(world, CLEAR_REFERENCE_X))
                && java.util.Arrays.equals(clear, nearbyLight(world, CLEAR_TARGET_X))
                && world.getHeightValue(CLEAR_TARGET_X, Z) == 0
                && load(world, CLEAR_TARGET_X >> 4, Z >> 4).heightMapMinimum == 0,
            stage + " last-block clear height/light");
    }

    private static int[] sectionLight(WorldServer world, int origin) {
        int[] values = new int[8192];
        int index = 0;
        for (int y = Y; y < Y + 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int[] light = light(world, origin + x, y, Z + z);
            values[index++] = light[0];
            values[index++] = light[1];
        }
        return values;
    }

    private static int differences(int[] expected, int[] actual) {
        int mismatches = 0;
        for (int i = 0; i < expected.length; i++) if (expected[i] != actual[i]) mismatches++;
        return mismatches;
    }

    private static void loadBox(WorldServer world, int origin) {
        for (int cx = (origin - 18) >> 4; cx <= (origin + 16 + 18) >> 4; cx++)
            for (int cz = (Z - 18) >> 4; cz <= (Z + 16 + 18) >> 4; cz++) load(world, cx, cz);
    }

    private static boolean observe(WorldServer world, NBTTagCompound oracle, String stage, boolean compare) {
        int skyMismatches = 0, blockMismatches = 0, heightMismatches = 0, samples = 0;
        NBTTagList differences = new NBTTagList();
        for (int dx = -1; dx <= SIDE; dx++) for (int dz = -1; dz <= SIDE; dz++) {
            if (world.getHeightValue(REFERENCE_X + dx, Z + dz) != world.getHeightValue(TARGET_X + dx, Z + dz))
                heightMismatches++;
            for (int dy : SAMPLE_Y) {
                int[] expected = light(world, REFERENCE_X + dx, Y + dy, Z + dz);
                int[] actual = light(world, TARGET_X + dx, Y + dy, Z + dz);
                samples++;
                if (expected[0] != actual[0]) skyMismatches++;
                if (expected[1] != actual[1]) blockMismatches++;
                if ((expected[0] != actual[0] || expected[1] != actual[1]) && differences.tagCount() < 32) {
                    NBTTagCompound difference = new NBTTagCompound();
                    difference.setIntArray("relative", new int[] { dx, dy, dz });
                    difference.setIntArray("nativeReference", expected);
                    difference.setIntArray("rawCut", actual);
                    differences.appendTag(difference);
                }
            }
        }
        NBTTagCompound report = new NBTTagCompound();
        report.setInteger("samples", samples);
        report.setInteger("skyMismatches", skyMismatches);
        report.setInteger("blockMismatches", blockMismatches);
        report.setInteger("heightMismatches", heightMismatches);
        report.setTag("firstDifferences", differences);
        report.setIntArray("referenceBelowRoof", light(world, REFERENCE_X + SIDE / 2, Y - 1, Z + SIDE / 2));
        report.setIntArray("rawBelowRoof", light(world, TARGET_X + SIDE / 2, Y - 1, Z + SIDE / 2));
        oracle.getCompoundTag(KEY)
            .setTag(stage, report);
        System.out.println("[GTMB-CUT-LIGHTING] " + stage + " " + report);
        if (compare) require(
            skyMismatches == 0 && blockMismatches == 0 && heightMismatches == 0,
            stage + " differs from ordinary native placement; " + report);
        return skyMismatches == 0 && blockMismatches == 0 && heightMismatches == 0;
    }

    private static int[] light(WorldServer world, int x, int y, int z) {
        Chunk chunk = load(world, x >> 4, z >> 4);
        return new int[] { chunk.getSavedLightValue(EnumSkyBlock.Sky, x & 15, y, z & 15),
            chunk.getSavedLightValue(EnumSkyBlock.Block, x & 15, y, z & 15) };
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError("[GTMB-CUT-LIGHTING] " + message);
    }

    private static Chunk load(WorldServer world, int x, int z) {
        Chunk chunk = world.theChunkProviderServer.loadChunk(x, z);
        require(
            chunk != null && !(chunk instanceof EmptyChunk) && chunk.xPosition == x && chunk.zPosition == z,
            "real native chunk required at " + x + "," + z);
        return chunk;
    }
}
