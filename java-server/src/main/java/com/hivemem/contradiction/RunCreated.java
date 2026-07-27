package com.hivemem.contradiction;

/**
 * Subset of Vistierie's RunCreatedResponse (202 Accepted), shared by {@link
 * VistierieContradictionClient} and {@link VistierieCardinalityClient} — both implement the
 * identical run-creation contract, so the response shape is single-sourced here.
 */
record RunCreated(String run_id, String agent_name, int agent_version, String status) {}
