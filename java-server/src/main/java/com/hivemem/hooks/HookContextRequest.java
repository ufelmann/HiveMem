package com.hivemem.hooks;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HookContextRequest(
        @JsonProperty("hook_event_name") String hook_event_name,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("session_id") String session_id,
        @JsonProperty("cwd") String cwd
) {}
