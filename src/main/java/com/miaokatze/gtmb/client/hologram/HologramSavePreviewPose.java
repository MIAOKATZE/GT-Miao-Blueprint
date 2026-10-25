package com.miaokatze.gtmb.client.hologram;

/** A save owns its observed camera angles; later UI navigation cannot change its cover. */
final class HologramSavePreviewPose {

    static final double DEFAULT_YAW = Math.PI / 4, DEFAULT_PITCH = Math.PI / 6;

    final long task, generation, revision;
    final String session;
    final double yaw, pitch;

    HologramSavePreviewPose(long task, long generation, HologramState source, double yaw, double pitch) {
        if (task <= 0 || source == null || !Double.isFinite(yaw) || !Double.isFinite(pitch))
            throw new IllegalArgumentException("Invalid save preview camera");
        this.task = task;
        this.generation = generation;
        session = source.session;
        revision = source.data.getLong("planRevision");
        this.yaw = yaw;
        this.pitch = pitch;
    }

    boolean sameSource(long task, long generation, String session) {
        return this.task == task && this.generation == generation && this.session.equals(session);
    }

    /** Only the save's exact published command may advance its click-time revision during preparation. */
    boolean accepts(long task, long generation, String session, long revision,
        HologramLibraryClient.SaveCommand command, HologramState publication) {
        if (!sameSource(task, generation, session) || revision < this.revision) return false;
        if (revision == this.revision) return true;
        return command != null && publication != null
            && command.matches(publication.data, task)
            && publication.session.equals(session)
            && publication.data.getLong("planRevision") == revision
            && publication.data.getBoolean("blueprintSavePending");
    }
}
