package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Order(37)
public class GetAttachmentInfoToolHandler implements ToolHandler {

    private final AttachmentRepository repo;

    public GetAttachmentInfoToolHandler(AttachmentRepository repo) {
        this.repo = repo;
    }

    @Override
    public String name() { return "get_attachment_info"; }

    @Override
    public String description() {
        return "Get metadata for a single attachment by ID. " +
               "Includes mime_type, size_bytes, s3_key_thumbnail (use GET /api/attachments/{id}/thumbnail to fetch preview). " +
               "Use GET /api/attachments/{id}/content to download the original file.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("attachment_id", "UUID of the attachment")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID id = UUID.fromString(arguments.get("attachment_id").asText());
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + id));
    }
}
