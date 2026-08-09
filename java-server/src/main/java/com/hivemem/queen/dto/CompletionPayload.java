package com.hivemem.queen.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Subset of Vistierie's completion-webhook payload that HiveMem consumes. */
public record CompletionPayload(
        @JsonProperty("run_id") String run_id,
        @JsonProperty("status") String status,
        @JsonProperty("output") Map<String, Object> output,
        @JsonProperty("error") String error,
        /** ISO-8601 instant string; used to bound the failed-run child-run recovery query. */
        @JsonProperty("started_at") String started_at
) {}
