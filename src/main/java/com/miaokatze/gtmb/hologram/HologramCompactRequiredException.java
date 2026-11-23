// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

/** Stored representation only. Passing this gate grants no content, world or construction authority. */
public final class HologramCompactRequiredException extends IOException {

    private static final long serialVersionUID = 1L;
    public static final String MESSAGE = "此蓝图必须先紧凑化，请在蓝图库选择「升级／紧凑化」";

    public HologramCompactRequiredException() {
        super(MESSAGE);
    }

    public static void require(byte[] source) throws IOException {
        if (!HologramBlueprintCompactCodec.isCompact(source, HologramBlueprintArtifact.MAX_JSON_BYTES))
            throw new HologramCompactRequiredException();
    }
}
