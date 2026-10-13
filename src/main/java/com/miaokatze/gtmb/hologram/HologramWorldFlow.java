package com.miaokatze.gtmb.hologram;

/** World gestures never authorize construction; that remains an explicit server-validated UI action. */
public final class HologramWorldFlow {

    public static final int DEFAULT_DISTANCE = 6, MIN_DISTANCE = 2, MAX_DISTANCE = 64;

    public enum Stage {
        WORKSPACE,
        AREA,
        MOVING,
        LOCKED,
        JOB
    }

    public enum Gesture {
        RIGHT,
        SHIFT_RIGHT,
        ALT_RIGHT,
        ENTER,
        ESCAPE,
        ROTATE
    }

    public enum Intent {
        NONE,
        OPEN,
        BEGIN_AREA,
        FINISH_AREA,
        CANCEL_AREA,
        CONFIRM_POSITION,
        UNLOCK_POSITION,
        QUICK_PLACE,
        CANCEL_PLACEMENT,
        ROTATE
    }

    private HologramWorldFlow() {}

    /** Use normalized view direction and floor, including negative coordinates and vertical camera angles. */
    public static int[] airAnchor(double x, double y, double z, double dx, double dy, double dz, int distance) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("view direction");
        int bounded = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
        return new int[] { (int) Math.floor(x + dx * bounded / length), (int) Math.floor(y + dy * bounded / length),
            (int) Math.floor(z + dz * bounded / length) };
    }

    /** A floor-selected voxel's center can lie less than one block past the camera ray's endpoint. */
    public static boolean withinPreviewReach(double eyeX, double eyeY, double eyeZ, int x, int y, int z) {
        double dx = x + .5 - eyeX, dy = y + .5 - eyeY, dz = z + .5 - eyeZ;
        double limit = MAX_DISTANCE + 1;
        return dx * dx + dy * dy + dz * dz <= limit * limit;
    }

    public static boolean worldPosition(int x, int y, int z, int height) {
        return x >= -30000000 && x < 30000000 && z >= -30000000 && z < 30000000 && y >= 0 && y < height;
    }

    /** Distance is local preview state; an inactive or new context cannot inherit previous wheel input. */
    public static final class PreviewDistance {

        private Object world;
        private String session;
        private boolean active;
        private int distance = DEFAULT_DISTANCE;

        public void sync(boolean enabled, Object currentWorld, String currentSession) {
            if (!enabled || !active || world != currentWorld || !java.util.Objects.equals(session, currentSession))
                distance = DEFAULT_DISTANCE;
            active = enabled;
            world = currentWorld;
            session = currentSession;
        }

        public int distance() {
            return distance;
        }

        public void scroll(int wheel) {
            if (!active || wheel == 0) return;
            long steps = Math.max(1L, Math.abs((long) wheel) / 120L);
            long next = distance + (wheel > 0 ? steps : -steps);
            distance = (int) Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, next));
        }
    }

    public static Stage stage(boolean area, boolean virtual, boolean placement, boolean locked, int job) {
        if (job == 1 || job == 2) return Stage.JOB;
        if (area) return Stage.AREA;
        if (virtual && placement && job != 3 && job != 4 && job != 5) return locked ? Stage.LOCKED : Stage.MOVING;
        return Stage.WORKSPACE;
    }

    public static Intent intent(Stage stage, Gesture gesture) {
        if (stage == Stage.AREA) {
            if (gesture == Gesture.SHIFT_RIGHT) return Intent.FINISH_AREA;
            if (gesture == Gesture.ESCAPE) return Intent.CANCEL_AREA;
            return Intent.NONE;
        }
        if (stage == Stage.MOVING) {
            if (gesture == Gesture.RIGHT) return Intent.CONFIRM_POSITION;
            if (gesture == Gesture.ESCAPE) return Intent.CANCEL_PLACEMENT;
            if (gesture == Gesture.ROTATE) return Intent.ROTATE;
            return Intent.NONE;
        }
        if (stage == Stage.LOCKED) {
            if (gesture == Gesture.SHIFT_RIGHT) return Intent.UNLOCK_POSITION;
            if (gesture == Gesture.RIGHT) return Intent.OPEN;
            if (gesture == Gesture.ESCAPE) return Intent.CANCEL_PLACEMENT;
            return Intent.NONE;
        }
        if (gesture == Gesture.RIGHT) return Intent.OPEN;
        if (gesture == Gesture.SHIFT_RIGHT && stage != Stage.JOB) return Intent.BEGIN_AREA;
        if (gesture == Gesture.ALT_RIGHT && stage == Stage.WORKSPACE) return Intent.QUICK_PLACE;
        return Intent.NONE;
    }

    /** Suppress keys held while changing tools, worlds or screens until the player releases them. */
    public static final class KeyEdge {

        private boolean down;

        public boolean sample(boolean pressed, boolean contextChanged) {
            boolean edge = pressed && !down && !contextChanged;
            down = pressed;
            return edge;
        }
    }

    /** One gesture consumes a use-item press until its actual binding is released. */
    public static final class UsePressFence {

        private boolean awaitingRelease;

        public void observe(boolean pressed, boolean contextChanged) {
            if (!pressed) awaitingRelease = false;
            else if (contextChanged) awaitingRelease = true;
        }

        public boolean consume() {
            if (awaitingRelease) return false;
            awaitingRelease = true;
            return true;
        }
    }
}
