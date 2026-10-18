package com.miaokatze.gtmb.hologram;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The 24 proper cube orientations, with a bounded canonical quarter-turn recipe. No world state. */
public final class HologramPreviewPose {

    public static final int MAX_RECIPE_LENGTH = 6;

    private final int[] matrix;
    private int[] canonicalRecipe;

    public HologramPreviewPose() {
        matrix = new int[] { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
    }

    private HologramPreviewPose(int[] matrix) {
        this.matrix = matrix;
    }

    public int[] apply(int x, int y, int z) {
        return new int[] { matrix[0] * x + matrix[1] * y + matrix[2] * z, matrix[3] * x + matrix[4] * y + matrix[5] * z,
            matrix[6] * x + matrix[7] * y + matrix[8] * z };
    }

    public HologramPreviewPose turn(boolean vertical) {
        int[] next = new int[9];
        for (int column = 0; column < 3; column++) {
            next[column] = vertical ? matrix[column] : -matrix[6 + column];
            next[3 + column] = vertical ? -matrix[6 + column] : matrix[3 + column];
            next[6 + column] = vertical ? matrix[3 + column] : matrix[column];
        }
        return new HologramPreviewPose(next);
    }

    /** 0 is a Y turn; 1 is an X turn. Every valid pose needs at most six turns. */
    public int[] recipe() {
        if (canonicalRecipe != null) return canonicalRecipe.clone();
        ArrayDeque<HologramPreviewPose> poses = new ArrayDeque<>();
        ArrayDeque<int[]> paths = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        poses.add(new HologramPreviewPose());
        paths.add(new int[0]);
        while (!poses.isEmpty()) {
            HologramPreviewPose pose = poses.remove();
            int[] path = paths.remove();
            if (!seen.add(Arrays.toString(pose.matrix))) continue;
            if (Arrays.equals(matrix, pose.matrix)) {
                canonicalRecipe = path;
                return path.clone();
            }
            for (int axis = 0; axis < 2; axis++) {
                poses.add(pose.turn(axis == 1));
                int[] next = Arrays.copyOf(path, path.length + 1);
                next[path.length] = axis;
                paths.add(next);
            }
        }
        throw new IllegalStateException("Invalid preview orientation");
    }
}
