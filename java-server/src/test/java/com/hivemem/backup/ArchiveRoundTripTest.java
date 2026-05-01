package com.hivemem.backup;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveRoundTripTest {

    @Test
    void writesAndReadsBackThreeEntries() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ArchiveWriter w = new ArchiveWriter(buf)) {
            w.addEntry("manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
            w.addEntry("postgres.sql.gz", new byte[]{1, 2, 3});
            w.addEntry("attachments/ab/abcdef", "hello".getBytes(StandardCharsets.UTF_8));
        }

        try (ArchiveReader r = new ArchiveReader(new ByteArrayInputStream(buf.toByteArray()))) {
            ArchiveReader.Entry e1 = r.nextEntry();
            assertEquals("manifest.json", e1.name());
            assertEquals("{}", new String(e1.read(), StandardCharsets.UTF_8));
            ArchiveReader.Entry e2 = r.nextEntry();
            assertEquals("postgres.sql.gz", e2.name());
            assertArrayEquals(new byte[]{1, 2, 3}, e2.read());
            ArchiveReader.Entry e3 = r.nextEntry();
            assertEquals("attachments/ab/abcdef", e3.name());
            assertEquals("hello", new String(e3.read(), StandardCharsets.UTF_8));
            assertNull(r.nextEntry());
        }
    }
}
