package com.hivemem.attachment;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class ParserRegistry {

    private final List<AttachmentParser> parsers;

    public ParserRegistry(List<AttachmentParser> parsers) {
        this.parsers = parsers;
    }

    public ParseResult parse(String mimeType, InputStream content) {
        for (AttachmentParser parser : parsers) {
            if (parser.supports(mimeType)) {
                try {
                    return parser.parse(content);
                } catch (Exception e) {
                    return ParseResult.empty();
                }
            }
        }
        return ParseResult.empty();
    }
}
