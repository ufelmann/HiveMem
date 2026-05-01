package com.hivemem.attachment;

import java.util.Locale;
import java.util.Map;

/** Re-maps generic MIMEs to specific Kroki-aware MIMEs based on filename extension. */
public final class MimeTypeResolver {

    private static final Map<String, String> EXTENSION_TO_MIME = Map.ofEntries(
            Map.entry("mmd",       "text/x-mermaid"),
            Map.entry("mermaid",   "text/x-mermaid"),
            Map.entry("puml",      "text/x-plantuml"),
            Map.entry("plantuml",  "text/x-plantuml"),
            Map.entry("dot",       "text/vnd.graphviz"),
            Map.entry("gv",        "text/vnd.graphviz"),
            Map.entry("d2",        "text/x-d2")
    );

    private MimeTypeResolver() {}

    /**
     * If the given mimeType is generic (text/plain, application/octet-stream, null/empty)
     * AND the filename's extension is in the diagram set, return the diagram-specific MIME.
     * Otherwise return the original mimeType.
     */
    public static String resolve(String mimeType, String filename) {
        if (filename == null) return mimeType;
        if (mimeType != null && !isGeneric(mimeType)) return mimeType;

        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return mimeType;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mapped = EXTENSION_TO_MIME.get(ext);
        return mapped != null ? mapped : mimeType;
    }

    private static boolean isGeneric(String mimeType) {
        return "text/plain".equalsIgnoreCase(mimeType)
                || "application/octet-stream".equalsIgnoreCase(mimeType);
    }
}
