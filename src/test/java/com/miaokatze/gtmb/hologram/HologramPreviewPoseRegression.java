package com.miaokatze.gtmb.hologram;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Pure orientation algebra: no Minecraft registry, client, world, networking or GL. */
public final class HologramPreviewPoseRegression {

    public static void main(String[] args) {
        run();
        System.out.println("Preview pose: 24 orientations, canonical recipes, inverse cycles and handedness PASS");
    }

    static void run() {
        Set<String> seen = new HashSet<>();
        ArrayDeque<HologramPreviewPose> pending = new ArrayDeque<>();
        pending.add(new HologramPreviewPose());
        while (!pending.isEmpty()) {
            HologramPreviewPose pose = pending.remove();
            String key = signature(pose);
            if (!seen.add(key)) continue;
            HologramPreviewPose restored = new HologramPreviewPose();
            int[] recipe = pose.recipe();
            check(recipe.length <= HologramPreviewPose.MAX_RECIPE_LENGTH, "canonical recipe must stay bounded");
            for (int axis : recipe) restored = restored.turn(axis == 1);
            check(key.equals(signature(restored)), "recipe must restore exact orientation");
            if (recipe.length != 0) recipe[0] = 99;
            for (int axis : pose.recipe()) check(axis == 0 || axis == 1, "callers cannot mutate cached recipe");
            int[] x = pose.apply(1, 0, 0), y = pose.apply(0, 1, 0), z = pose.apply(0, 0, 1);
            int determinant = x[0] * (y[1] * z[2] - y[2] * z[1]) - y[0] * (x[1] * z[2] - x[2] * z[1])
                + z[0] * (x[1] * y[2] - x[2] * y[1]);
            check(determinant == 1, "pose must preserve handedness");
            int[] point = pose.apply(7, -11, 23);
            check(
                point[0] * point[0] + point[1] * point[1] + point[2] * point[2] == 699,
                "pose must preserve distances");
            for (boolean vertical : new boolean[] { false, true }) {
                restored = pose;
                for (int i = 0; i < 4; i++) restored = restored.turn(vertical);
                check(key.equals(signature(restored)), "four quarter turns must restore pose");
                pending.add(pose.turn(vertical));
            }
        }
        check(seen.size() == 24, "cube orientation group must contain exactly 24 poses");
        HologramPreviewPose identity = new HologramPreviewPose();
        check(
            !signature(
                identity.turn(false)
                    .turn(true)).equals(
                        signature(
                            identity.turn(true)
                                .turn(false))),
            "horizontal and vertical turns must not commute");
    }

    private static String signature(HologramPreviewPose pose) {
        return Arrays.toString(pose.apply(1, 0, 0)) + Arrays.toString(pose.apply(0, 1, 0))
            + Arrays.toString(pose.apply(0, 0, 1));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
