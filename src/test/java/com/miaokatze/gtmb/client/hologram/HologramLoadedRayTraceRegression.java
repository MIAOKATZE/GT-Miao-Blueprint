package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;

/** Loaded-world traversal contracts; no Minecraft world, chunk loading or OpenGL required. */
public final class HologramLoadedRayTraceRegression {

    private HologramLoadedRayTraceRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("HologramLoadedRayTraceRegression passed");
    }

    static void run() {
        List<Integer> visited = new ArrayList<>();
        HologramLoadedRayTrace.Result<Integer> visible = HologramLoadedRayTrace.trace(
            new CameraRay(.5, 64.5, .5, 1, 0, 0),
            0,
            200,
            (x, y, z) -> x >= 144 && x <= 160,
            (x, y, z, from, to) -> {
                visited.add(x);
                SimpleAssert
                    .that(from == x - .5 && to == x + .5, "trace endpoints bound only the checked loaded voxel");
                return x == 150 ? x : null;
            });
        SimpleAssert.that(visible.available, "unloaded near-plane space permits the later visible loaded target");
        SimpleAssert.eq(Integer.valueOf(150), visible.hit, "the actual loaded target is hit");
        SimpleAssert
            .eq(Integer.valueOf(144), visited.get(0), "no collision lookup occurs before entering loaded space");

        visited.clear();
        HologramLoadedRayTrace.Result<Integer> gap = HologramLoadedRayTrace.trace(
            new CameraRay(.1, 64.5, .15, 1, 0, 1),
            0,
            4,
            (x, y, z) -> !(x == 0 && z == 1),
            (x, y, z, from, to) -> {
                visited.add(x * 10 + z);
                return x == 1 && z == 1 ? 11 : null;
            });
        SimpleAssert.that(
            !gap.available && gap.hit == null,
            "a missing interval shorter than the former half-block sampling step blocks the later target");
        SimpleAssert.eq(1, visited.size(), "collision lookup never crosses an intermediate unloaded gap");

        HologramLoadedRayTrace.Result<Integer> negative = HologramLoadedRayTrace.trace(
            new CameraRay(160, 64.5, .5, -1, 0, 0),
            0,
            30,
            (x, y, z) -> x <= 150 && x >= 140,
            (x, y, z, from, to) -> {
                SimpleAssert.that(
                    from == 159 - x && to == 160 - x,
                    "negative-going boundary endpoints remain in the entered voxel");
                return x == 148 ? x : null;
            });
        SimpleAssert.eq(Integer.valueOf(148), negative.hit, "negative rays skip the leading unloaded region correctly");
        HologramLoadedRayTrace.Result<Integer> absent = HologramLoadedRayTrace.trace(
            new CameraRay(0, 64.5, 0, 1, 0, 0),
            0,
            16,
            (x, y, z) -> false,
            (x, y, z, from, to) -> { throw new AssertionError("unloaded voxel reached collision lookup"); });
        SimpleAssert.that(!absent.available, "an entirely unloaded ray has no valid target");
    }
}
