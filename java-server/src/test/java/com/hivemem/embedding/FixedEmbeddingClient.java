package com.hivemem.embedding;

import java.util.List;

public final class FixedEmbeddingClient implements EmbeddingClient {

    @Override
    public List<Float> encodeDocument(String text) {
        return encode(text);
    }

    @Override
    public List<Float> encodeQuery(String text) {
        return encode(text);
    }

    private static List<Float> encode(String text) {
        String value = text == null ? "" : text.toLowerCase();
        if (value.contains("semantic oracle")) {
            return List.of(1.0f, 0.0f, 0.0f);
        }
        if (value.contains("keyword oracle")) {
            return List.of(0.0f, 1.0f, 0.0f);
        }
        if (value.contains("duplicate oracle")) {
            return List.of(0.9f, 0.9f, 0.0f);
        }
        if (value.contains("semantic")) {
            return List.of(1.0f, 0.0f, 0.0f);
        }
        if (value.contains("keyword")) {
            return List.of(0.0f, 1.0f, 0.0f);
        }
        if (value.contains("recent")) {
            return List.of(0.0f, 0.0f, 1.0f);
        }
        int hash = Math.abs(value.hashCode());
        return List.of(
                (hash % 100) / 100.0f,
                ((hash / 100) % 100) / 100.0f,
                ((hash / 10000) % 100) / 100.0f
        );
    }
}
