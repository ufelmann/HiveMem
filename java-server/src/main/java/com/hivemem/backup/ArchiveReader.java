package com.hivemem.backup;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public final class ArchiveReader implements AutoCloseable {

    private final TarArchiveInputStream tar;

    public ArchiveReader(InputStream in) throws IOException {
        this.tar = new TarArchiveInputStream(new GZIPInputStream(in));
    }

    public Entry nextEntry() throws IOException {
        TarArchiveEntry e = tar.getNextEntry();
        if (e == null) return null;
        return new Entry(e.getName(), e.getSize(), tar);
    }

    @Override
    public void close() throws IOException {
        tar.close();
    }

    public record Entry(String name, long size, InputStream stream) {
        public byte[] read() throws IOException {
            return stream.readAllBytes();
        }
    }
}
