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

    void directoryRecovered() {
        if (directoryError) show("");
    }

    String message() {
        return message;
    }
}
