package com.hivemem.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FixedEmbeddingClient implements EmbeddingClient {

    private final int dims;
    private final String model;

    public FixedEmbeddingClient() {
        this(1024, "test-model");
    }

    public FixedEmbeddingClient(int dims, String model) {
        this.dims = dims;
        this.model = model;
    }

    @Override
    public List<Float> encodeDocument(String text) {
        return encode(text);
    }

    @Override
    public List<Float> encodeQuery(String text) {
        return encode(text);
    }

    @Override
    public EmbeddingInfo getInfo() {
        return new EmbeddingInfo(model, dims);
    }

    private List<Float> encode(String text) {
        String value = text == null ? "" : text.toLowerCase();
        if (value.contains("semantic oracle")) {
            return pad(1.0f, 0.0f, 0.0f);
        }
        if (value.contains("keyword oracle")) {
            return pad(0.0f, 1.0f, 0.0f);
        }
        if (value.contains("duplicate oracle")) {
            return pad(0.9f, 0.9f, 0.0f);
        }
        if (value.contains("semantic")) {
            return pad(1.0f, 0.0f, 0.0f);
        }
        if (value.contains("keyword")) {
            return pad(0.0f, 1.0f, 0.0f);
        }
        if (value.contains("recent")) {
            return pad(0.0f, 0.0f, 1.0f);
        }
        int hash = Math.abs(value.hashCode());
        return pad(
                (hash % 100) / 100.0f,
                ((hash / 100) % 100) / 100.0f,
                ((hash / 10000) % 100) / 100.0f
        );
    }

    private List<Float> pad(float d0, float d1, float d2) {
        List<Float> vec = new ArrayList<>(Collections.nCopies(dims, 0.0f));
        vec.set(0, d0);
        vec.set(1, d1);
        vec.set(2, d2);
        return Collections.unmodifiableList(vec);
    }
}
