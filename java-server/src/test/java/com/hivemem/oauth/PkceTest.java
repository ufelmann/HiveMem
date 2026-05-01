package com.hivemem.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PkceTest {

    // RFC 7636 Appendix B test vector
    private static final String RFC7636_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC7636_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void s256ChallengeMatchesRfc7636Vector() {
        assertEquals(RFC7636_CHALLENGE, Pkce.computeS256Challenge(RFC7636_VERIFIER));
    }

    @Test
    void verifyAcceptsCorrectVerifier() {
        assertTrue(Pkce.verify(RFC7636_VERIFIER, RFC7636_CHALLENGE, "S256"));
    }

    @Test
    void verifyRejectsWrongVerifier() {
        assertFalse(Pkce.verify("wrong-verifier", RFC7636_CHALLENGE, "S256"));
    }

    @Test
    void verifyRejectsUnsupportedMethod() {
        assertFalse(Pkce.verify(RFC7636_VERIFIER, RFC7636_VERIFIER, "plain"));
    }

    @Test
    void verifyRejectsEmptyInputs() {
        assertFalse(Pkce.verify("", RFC7636_CHALLENGE, "S256"));
        assertFalse(Pkce.verify(RFC7636_VERIFIER, "", "S256"));
        assertFalse(Pkce.verify(RFC7636_VERIFIER, RFC7636_CHALLENGE, ""));
        assertFalse(Pkce.verify(null, RFC7636_CHALLENGE, "S256"));
    }
}
