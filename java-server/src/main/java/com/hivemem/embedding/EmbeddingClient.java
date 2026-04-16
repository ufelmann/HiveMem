package com.hivemem.embedding;

import java.util.List;

public interface EmbeddingClient {

    List<Float> encodeDocument(String text);

    default List<Float> encodeQuery(String text) {
        return encodeDocument(text);
    }

    EmbeddingInfo getInfo();
}
