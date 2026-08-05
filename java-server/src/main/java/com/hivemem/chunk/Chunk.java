package com.hivemem.chunk;

/**
 * A single passage produced by {@link CellChunker}, in reading order.
 *
 * @param ordinal 0-based position within the cell, in reading order
 * @param pageFrom first page number covered by this chunk, or {@code null} if the source content
 *     carried no recognized page markers
 * @param pageTo last page number covered by this chunk, or {@code null} under the same condition
 *     as {@code pageFrom}
 * @param content the chunk's text, exactly as it appears in the original content (no trimming)
 */
public record Chunk(int ordinal, Integer pageFrom, Integer pageTo, String content) {}
