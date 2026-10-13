package com.miaokatze.gtmb.hologram;

/** World gestures never authorize construction; that remains an explicit server-validated UI action. */
public final class HologramWorldFlow {

    public static final int DEFAULT_DISTANCE = 6, MIN_DISTANCE = 2, MAX_DISTANCE = 64;
    public static final int NUDGE_REPEAT_DELAY = 8, NUDGE_REPEAT_INTERVAL = 4;

    public enum Nudge {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

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

    /** Minecraft yaw zero faces south; left/right stay horizontal even when the camera looks vertically. */
    public static int[] nudgeOffset(double yaw, Nudge direction) {
        if (!Double.isFinite(yaw)) throw new IllegalArgumentException("camera yaw");
        if (direction == Nudge.UP) return new int[] { 0, 1, 0 };
        if (direction == Nudge.DOWN) return new int[] { 0, -1, 0 };
        int quadrant = Math.floorMod((int) Math.floor((yaw % 360 + 45) / 90), 4);
        int[] forwardX = { 0, -1, 0, 1 }, forwardZ = { 1, 0, -1, 0 };
        int x = forwardX[quadrant], z = forwardZ[quadrant];
        if (direction == Nudge.FORWARD) return new int[] { x, 0, z };
        if (direction == Nudge.BACKWARD) return new int[] { -x, 0, -z };
        if (direction == Nudge.LEFT) return new int[] { z, 0, -x };
        if (direction == Nudge.RIGHT) return new int[] { -z, 0, x };
        throw new IllegalArgumentException("nudge direction");
    }

    public static boolean unitNudge(int dx, int dy, int dz) {
        return Math.abs((long) dx) + Math.abs((long) dy) + Math.abs((long) dz) == 1;
    }

    /** One initial step, then a bounded repeat; entering a screen/tool context with held keys requires release. */
    public static final class KeyRepeat {

        private boolean down, blocked, repeated;
        private long nextTick;

        public boolean sample(boolean pressed, boolean enabled, boolean contextChanged, long tick) {
            repeated = false;
            if (!pressed) {
                down = blocked = false;
                return false;
            }
            if (!enabled || contextChanged) {
                down = blocked = true;
                return false;
            }
            if (blocked) return false;
            if (!down) {
                down = true;
                nextTick = tick + NUDGE_REPEAT_DELAY;
                return true;
            }
            if (tick < nextTick) return false;
            repeated = true;
            nextTick = tick + NUDGE_REPEAT_INTERVAL;
            return true;
        }

        public boolean repeated() {
            return repeated;
        }
    }

    /** Shared interval and revision ACK for all arrows, with one queued deliberate step. */
    public static final class NudgeDispatch {

        private String session = "";
        private long pendingRevision = -1, pendingTick, lastSentTick = Long.MIN_VALUE;
        private int[] queued;
        private boolean queuedRepeat;

        public void sync(String currentSession, long revision, long tick, boolean contextChanged) {
            if (contextChanged || !session.equals(currentSession)) {
                session = currentSession;
                pendingRevision = -1;
                lastSentTick = Long.MIN_VALUE;
                queued = null;
                return;
            }
            if (pendingRevision >= 0 && revision > pendingRevision) pendingRevision = -1;
            else if (pendingRevision >= 0 && tick - pendingTick >= 20) {
                // Recovery from a rejected/lost request is distinct from a successful revision ACK.
                pendingRevision = -1;
            }
        }

        public void queue(int[] offset, boolean repeat) {
            if (offset == null || offset.length != 3 || !unitNudge(offset[0], offset[1], offset[2]))
                throw new IllegalArgumentException("nudge offset");
            if (repeat && queued != null && !queuedRepeat) return;
            queued = offset.clone();
            queuedRepeat = repeat;
        }

        public void releaseRepeats() {
            if (queuedRepeat) queued = null;
        }

        public int[] poll(long revision, long tick) {
            if (session.isEmpty() || queued == null
                || pendingRevision >= 0
                || lastSentTick != Long.MIN_VALUE && tick - lastSentTick < NUDGE_REPEAT_INTERVAL) return null;
            int[] offset = queued;
            queued = null;
            pendingRevision = revision;
            pendingTick = lastSentTick = tick;
            return offset;
        }
    }

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
