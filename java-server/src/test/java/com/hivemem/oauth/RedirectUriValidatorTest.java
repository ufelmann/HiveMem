package com.hivemem.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectUriValidatorTest {

    @Test
    void httpsIsAccepted() {
        assertTrue(RedirectUriValidator.isAcceptable("https://claude.ai/api/mcp/auth_callback"));
    }

    @Test
    void httpLoopbackIsAccepted() {
        assertTrue(RedirectUriValidator.isAcceptable("http://127.0.0.1:54321/callback"));
        assertTrue(RedirectUriValidator.isAcceptable("http://localhost:54321/callback"));
    }

    @Test
    void httpNonLoopbackIsRejected() {
        assertFalse(RedirectUriValidator.isAcceptable("http://example.com/callback"));
        assertFalse(RedirectUriValidator.isAcceptable("http://192.168.1.1/callback"));
    }

    @Test
    void customSchemesAreAccepted() {
        assertTrue(RedirectUriValidator.isAcceptable("claude://oauth/callback"));
        assertTrue(RedirectUriValidator.isAcceptable("com.example.app://oauth"));
    }

    @Test
    void malformedUrisRejected() {
        assertFalse(RedirectUriValidator.isAcceptable(""));
        assertFalse(RedirectUriValidator.isAcceptable(null));
        assertFalse(RedirectUriValidator.isAcceptable("not a uri"));
        assertFalse(RedirectUriValidator.isAcceptable("javascript:alert(1)"));
        assertFalse(RedirectUriValidator.isAcceptable("file:///etc/passwd"));
    }

    @Test
    void uriFragmentRejected() {
        assertFalse(RedirectUriValidator.isAcceptable("https://example.com/cb#fragment"));
    }
}
