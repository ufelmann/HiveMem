package com.hivemem.attachment;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads diagram source text for Kroki-supported MIME types.
 * Registered with @Order(10) so it precedes the generic TextAttachmentParser.
 * Thumbnail rendering happens async in AttachmentEnrichmentService.
 */
@Component
@Order(10)
public class KrokiAttachmentParser implements AttachmentParser {

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && KrokiClient.MIME_TO_FORMAT.containsKey(mimeType);
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        String text = new String(content.readAllBytes(), StandardCharsets.UTF_8).strip();
        return ParseResult.textOnly(text.isEmpty() ? null : text);
    }
}
