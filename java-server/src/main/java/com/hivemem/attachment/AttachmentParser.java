package com.hivemem.attachment;

import java.io.InputStream;

public interface AttachmentParser {
    boolean supports(String mimeType);
    ParseResult parse(InputStream content) throws Exception;
}
