package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.attachment.AttachmentService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
@Order(35)
public class UploadAttachmentToolHandler implements ToolHandler {

    private final AttachmentService service;

    public UploadAttachmentToolHandler(AttachmentService service) {
        this.service = service;
    }

    @Override
    public String name() { return "upload_attachment"; }

    @Override
    public String description() {
        return "Upload a file attachment and link it to a cell. " +
               "Pass file content as Base64 in the `data` field. " +
               "Returns attachment metadata including extracted_text and thumbnail key if available. " +
               "For large files, prefer the HTTP endpoint POST /api/attachments (multipart).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("cell_id", "UUID of the cell to link this attachment to")
                .requiredString("filename", "Original filename including extension (e.g. report.pdf)")
                .requiredString("mime_type", "MIME type (e.g. application/pdf, image/png, message/rfc822)")
                .requiredString("data", "Base64-encoded file content")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (principal.role() == AuthRole.READER) throw new IllegalArgumentException("Reader role cannot upload");
        UUID cellId = UUID.fromString(arguments.get("cell_id").asText());
        String filename = arguments.get("filename").asText();
        String mimeType = arguments.get("mime_type").asText();
        byte[] bytes = Base64.getDecoder().decode(arguments.get("data").asText());
        try {
            return service.ingest(new ByteArrayInputStream(bytes), filename, mimeType, cellId, principal.name());
        } catch (Exception e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }
}
