package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/** Streams the already reviewed export once; cell checkpoints add no second blueprint traversal. */
final class HologramDraftEncoding {

    interface Progress {

        void update(long done, long total) throws IOException;

        default void checkpoint() throws IOException {}
    }

    private static final class Share {

        final int schema = 2;
        final HologramPresets.Preset blueprint;

        Share(HologramPresets.Preset blueprint) {
            this.blueprint = blueprint;
        }
    }

    private HologramDraftEncoding() {}

    static byte[] encode(HologramPresets.Preset blueprint, Progress progress) throws IOException {
        progress.checkpoint();
        HologramBlueprintVersions.validate(blueprint);
        long total = (long) blueprint.cells.size() + blueprint.palette.size();
        progress.update(0, total);
        Gson gson = new GsonBuilder().registerTypeAdapterFactory(new TypeAdapterFactory() {

            long done;

            @Override
            public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                if (type.getRawType() != HologramPresets.Cell.class) return null;
                TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
                return new TypeAdapter<T>() {

                    @Override
                    public void write(JsonWriter output, T value) throws IOException {
                        delegate.write(output, value);
                        progress.update(++done, total);
                    }

                    @Override
                    public T read(JsonReader input) throws IOException {
                        return delegate.read(input);
                    }
                };
            }
        })
            .create();
        byte[] encoded = HologramBlueprintSharing
            .encodeBounded(gson, new Share(blueprint), HologramBlueprintSharing.MAX_BYTES, progress::checkpoint);
        progress.update(total, total);
        return encoded;
    }
}
