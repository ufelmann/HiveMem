package com.hivemem.attachment;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Properties;

@Component
public class EmlAttachmentParser implements AttachmentParser {

    @Override
    public boolean supports(String mimeType) {
        return "message/rfc822".equals(mimeType);
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage message = new MimeMessage(session, content);
        StringBuilder sb = new StringBuilder();

        String subject = message.getSubject();
        if (subject != null) sb.append("Subject: ").append(subject).append("\n");

        String from = message.getHeader("From", null);
        if (from != null) sb.append("From: ").append(from).append("\n\n");

        appendTextParts(message.getContent(), sb);
        String text = sb.toString().strip();
        return ParseResult.textOnly(text.isEmpty() ? null : text);
    }

    private void appendTextParts(Object content, StringBuilder sb) throws Exception {
        if (content instanceof String s) {
            sb.append(s).append("\n");
        } else if (content instanceof MimeMultipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType().toLowerCase();
                if (ct.startsWith("text/plain")) {
                    appendTextParts(part.getContent(), sb);
                } else if (ct.startsWith("multipart/")) {
                    appendTextParts(part.getContent(), sb);
                }
            }
        }
    }
}
