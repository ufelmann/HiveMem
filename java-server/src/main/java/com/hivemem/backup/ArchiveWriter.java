package com.hivemem.backup;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public final class ArchiveWriter implements AutoCloseable {

    private final TarArchiveOutputStream tar;

    public ArchiveWriter(OutputStream out) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(out);
        this.tar = new TarArchiveOutputStream(gz);
        this.tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        this.tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
    }

    public void addEntry(String name, byte[] data) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(data.length);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    public void addEntry(String name, InputStream data, long size) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(size);
        tar.putArchiveEntry(entry);
        data.transferTo(tar);
        tar.closeArchiveEntry();
    }

    @Override
    public void close() throws IOException {
        tar.finish();
        tar.close();
    }
}
