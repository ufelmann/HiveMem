package com.hivemem.attachment;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EmlAttachmentParserTest {

    private final EmlAttachmentParser parser = new EmlAttachmentParser();

    @Test
    void supportsEml() {
        assertThat(parser.supports("message/rfc822")).isTrue();
        assertThat(parser.supports("application/pdf")).isFalse();
    }

    @Test
    void extractsSubjectAndBody() throws Exception {
        String eml = """
                From: sender@example.com
                To: receiver@example.com
                Subject: Test Email Subject
                Content-Type: text/plain; charset=UTF-8
                
                This is the email body text.
                """;
        ParseResult result = parser.parse(new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.extractedText()).contains("Test Email Subject");
        assertThat(result.extractedText()).contains("This is the email body text.");
        assertThat(result.hasThumbnail()).isFalse();
    }
}
