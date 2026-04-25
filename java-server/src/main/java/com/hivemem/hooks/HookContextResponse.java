package com.hivemem.hooks;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HookContextResponse(
        @JsonProperty("hookSpecificOutput") HookSpecificOutput hookSpecificOutput
) {
    public record HookSpecificOutput(
            @JsonProperty("hookEventName") String hookEventName,
            @JsonProperty("additionalContext") String additionalContext
    ) {}

    public static HookContextResponse of(String eventName, String additionalContext) {
        return new HookContextResponse(new HookSpecificOutput(eventName, additionalContext));
    }
}
