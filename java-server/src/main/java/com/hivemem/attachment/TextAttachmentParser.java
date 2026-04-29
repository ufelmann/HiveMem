package com.hivemem.attachment;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class TextAttachmentParser implements AttachmentParser {

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && mimeType.startsWith("text/");
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        String text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
        return ParseResult.textOnly(text.isBlank() ? null : text.strip());
    }
}
