package com.hivemem.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hooks")
public class HooksController {

    private static final Logger log = LoggerFactory.getLogger(HooksController.class);

    private final HookContextService service;

    public HooksController(HookContextService service) {
        this.service = service;
    }

    @PostMapping("/context")
    public ResponseEntity<HookContextResponse> context(@RequestBody HookContextRequest req) {
        String additional;
        try {
            additional = service.contextFor(req);
        } catch (RuntimeException e) {
            log.warn("Hook context failed; returning empty injection", e);
            additional = "";
        }
        String eventName = req != null && req.hook_event_name() != null
                ? req.hook_event_name() : "UserPromptSubmit";
        return ResponseEntity.ok(HookContextResponse.of(eventName, additional));
    }
}
