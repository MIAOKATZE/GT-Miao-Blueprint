package com.miaokatze.gtmb.hologram;

/** Explicit local-author boundary; direct integration APIs retain their synchronous caller-thread contract. */
public final class HologramExtensionExecution {

    private static final ThreadLocal<Scope> LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SERVER_REVIEW = new ThreadLocal<>();

    private HologramExtensionExecution() {}

    public static Scope localAuthor() {
        Scope scope = new Scope(LOCAL.get(), Boolean.TRUE.equals(SERVER_REVIEW.get()), false);
        if (!scope.serverReview) LOCAL.set(scope);
        return scope;
    }

    /** Guard the existing builtin-only library IO path against concurrent external registrations. */
    static Scope builtinLibraryIo() {
        Scope scope = new Scope(LOCAL.get(), false, true);
        LOCAL.set(scope);
        return scope;
    }

    public static void requireBuiltinIo(boolean builtin, String id) {
        if (builtin) return;
        for (Scope current = LOCAL.get(); current != null; current = current.parent) if (current.builtinOnly) {
            reject(id);
            return;
        }
    }

    static AutoCloseable serverReview() {
        if (!HologramWorkBudget.isServerOwnerThread() || LOCAL.get() != null)
            throw new IllegalStateException("Extension review requires the server owner thread");
        Boolean previous = SERVER_REVIEW.get();
        SERVER_REVIEW.set(true);
        return () -> {
            if (previous == null) SERVER_REVIEW.remove();
            else SERVER_REVIEW.set(previous);
        };
    }

    public static boolean localAuthorActive() {
        return LOCAL.get() != null;
    }

    /** Check before invoking even a provider's applicability callback; never fall through to a builtin. */
    public static void requireClientSafe(boolean clientSafe, String id) {
        Scope scope = LOCAL.get();
        if (clientSafe || scope == null) return;
        reject(id);
    }

    private static void reject(String id) {
        ReviewRequired failure = new ReviewRequired(id);
        for (Scope current = LOCAL.get(); current != null; current = current.parent) current.failure = failure;
        throw failure;
    }

    public static final class ReviewRequired extends IllegalStateException {

        final String extensionId;

        private ReviewRequired(String id) {
            super("Extension requires explicit server review: " + id);
            extensionId = id;
        }
    }

    public static final class Scope implements AutoCloseable {

        private final Scope parent;
        private final boolean serverReview, builtinOnly;
        private ReviewRequired failure;
        private boolean closed;

        private Scope(Scope parent, boolean serverReview, boolean builtinOnly) {
            this.parent = parent;
            this.serverReview = serverReview;
            this.builtinOnly = builtinOnly;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (serverReview) return;
            if (parent == null) LOCAL.remove();
            else LOCAL.set(parent);
            // Internal inspectors sometimes catch failures and show partial diagnostics. Such a fallback cannot
            // make an automatic author operation succeed after bypassing a legacy extension.
            if (failure != null) throw new ReviewRequired(failure.extensionId);
        }
    }
}
