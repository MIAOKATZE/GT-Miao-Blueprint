package com.miaokatze.gtmb.hologram;

import java.io.IOException;

/** Streams compact dictionaries and cell references with cancellable checkpoints. */
final class HologramDraftEncoding {

    interface Progress {

        void update(long done, long total) throws IOException;

        default void checkpoint() throws IOException {}
    }

    private HologramDraftEncoding() {}

    static byte[] encode(HologramPresets.Preset blueprint, Progress progress) throws IOException {
        progress.checkpoint();
        HologramBlueprintVersions.validate(blueprint);
        return HologramBlueprintCompactCodec.encode(blueprint, progress);
    }
}
