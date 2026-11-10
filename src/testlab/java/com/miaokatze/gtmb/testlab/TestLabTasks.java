package com.miaokatze.gtmb.testlab;

import java.io.IOException;

final class TestLabTasks {

    static final int CONTINUE = 0, WAIT = 1, DONE = 2;

    interface Task {

        int step() throws Exception;

        default void cleanup() throws Exception {}
    }

    private TestLabTasks() {}

    static Task create(TestLabRuntime runtime, String kind) throws Exception {
        if ("reset".equals(kind) || "refresh".equals(kind))
            return new TestLabResetTask(runtime, "refresh".equals(kind));
        if ("recovery-stage".equals(kind) || "recovery-resume".equals(kind))
            return new TestLabRecoveryTask(runtime, "recovery-resume".equals(kind));
        if ("construction-performance".equals(kind)) return new TestLabConstructionPerformanceTask(runtime);
        if ("minimal".equals(kind)) return new TestLabMinimalTask(runtime);
        if ("stress".equals(kind)) return new TestLabStressTask(runtime);
        if ("composite".equals(kind) && runtime.config().composite) return new TestLabCompositeTask(runtime);
        if (("native-build".equals(kind) || "native-test".equals(kind)) && runtime.config().nativePlatforms)
            return new TestLabNativeTask(runtime, "native-build".equals(kind));
        throw new IOException("Unknown bounded lab task");
    }

    static void require(boolean valid, String detail) throws IOException {
        if (!valid) throw new IOException(detail);
    }
}
