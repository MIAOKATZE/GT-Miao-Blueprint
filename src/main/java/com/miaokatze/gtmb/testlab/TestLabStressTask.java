package com.miaokatze.gtmb.testlab;

import java.util.concurrent.atomic.AtomicInteger;

import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** Explicit opt-in command only; finite work and cooperative stop between codec/frame operations. */
final class TestLabStressTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private TestLabCodecWork codec;
    private final AtomicInteger executed = new AtomicInteger();
    private int submitted;
    private boolean codecComplete;

    TestLabStressTask(TestLabRuntime runtime) {
        this.runtime = runtime;
    }

    public int step() throws Exception {
        if (codec == null) {
            runtime.useZone(runtime.config().zones.get(4));
            codec = new TestLabCodecWork(runtime.config().payloadKiB);
            runtime.evidence().data.setBoolean("syntheticInMemoryFrames", true);
            return TestLabTasks.CONTINUE;
        }
        if (submitted - executed.get() >= 8) return TestLabTasks.WAIT;
        if (!codecComplete) codecComplete = codec.step(runtime.evidence(), runtime.config().iterations);
        long completed = runtime.evidence().data.getCompoundTag("counters")
            .getLong("codecIterations");
        if (submitted < completed) {
            final TestLabReport target = runtime.evidence();
            submitted++;
            target.count("serverTasksSubmitted", 1);
            target.data.getCompoundTag("counters")
                .setLong(
                    "testTaskQueueHighWater",
                    Math.max(
                        target.data.getCompoundTag("counters")
                            .getLong("testTaskQueueHighWater"),
                        submitted - executed.get()));
            ServerTaskScheduler.scheduleServerTask(() -> {
                // A cancelled or later run cannot receive a stale callback or a world mutation.
                if (runtime.active() && runtime.evidence() == target) {
                    executed.incrementAndGet();
                    target.count("serverTasksExecuted", 1);
                }
            });
        }
        if (!codecComplete) return TestLabTasks.CONTINUE;
        if (executed.get() != submitted) return TestLabTasks.WAIT;
        runtime.evidence()
            .check(
                "codec_frame_assembly",
                "PASS",
                "Actual production compression/decode, cut frame codec and ordered assembly, entirely in memory");
        runtime.evidence()
            .check(
                "stress_bound",
                "PASS",
                "Configured finite iterations=" + runtime.config().iterations
                    + ", payloadKiB="
                    + runtime.config().payloadKiB
                    + "; not a FakePlayer Netty throughput claim");
        runtime.evidence()
            .check(
                "bounded_server_task_queue",
                "PASS",
                "Real production scheduler callbacks submitted/executed=" + submitted
                    + "; outstanding window <=8; cancellation leaves only inert callbacks");
        return TestLabTasks.DONE;
    }
}
