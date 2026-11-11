// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** A successful background directory read can clear only a directory error, never an operation result. */
final class HologramCutNotice {

    private String message = "";
    private boolean directoryError;

    void show(String value) {
        message = value;
        directoryError = false;
    }

    void directoryFailed(String value) {
        message = value;
        directoryError = true;
    }

    void operationResult(String value, String code) {
        if ("recovery_pending".equals(code)) directoryFailed(value);
        else show(value);
    }

    void directoryRecovered() {
        if (directoryError) show("");
    }

    String message() {
        return message;
    }
}
