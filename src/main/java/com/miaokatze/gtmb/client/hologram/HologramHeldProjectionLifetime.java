// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/**
 * A world projection survives while held and for five seconds away; only a new explicit projection can renew expiry.
 */
final class HologramHeldProjectionLifetime {

    static final long AWAY_NANOS = 5_000_000_000L;
    private boolean away, expired;
    private long awaySince;

    boolean observe(boolean held, long now) {
        if (!visible(now)) {
            expired = true;
            return false;
        }
        if (held) away = false;
        else if (!away) {
            away = true;
            awaySince = now;
        }
        return true;
    }

    boolean visible(long now) {
        return !expired && (!away || now - awaySince < AWAY_NANOS);
    }
}
