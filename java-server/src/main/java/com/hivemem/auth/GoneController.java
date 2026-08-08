package com.hivemem.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Access mode replacement for the legacy login endpoints. Without this, SpaController's
 * method-agnostic catch-all would forward /login and /logout to index.html with a 200,
 * leaving stale PWA shells silently broken instead of diagnosable.
 *
 * <p>{@code GET /login} is the one exception, and it is not a leftover: Cloudflare Access
 * returns the browser to the URL that triggered the challenge, and /login is the URL the SPA
 * navigates to for re-auth precisely because the service worker does not answer it from its
 * precache. A 410 there means a successful login lands on a blank page, so it redirects back
 * into the app instead. There is nothing to guard — the request only reaches the origin with
 * a valid Access session, and it exposes no credential path of its own.
 */
@RestController
@ConditionalOnProperty(name = "hivemem.access.enabled", havingValue = "true")
public class GoneController {

    @GetMapping("/login")
    public ResponseEntity<Void> loginReturnsToApp() {
        return ResponseEntity.status(HttpStatus.FOUND)
                // no-store: a cached redirect would outlive the session it was issued for.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .location(URI.create("/"))
                .build();
    }

    @RequestMapping({"/login", "/logout"})
    public ResponseEntity<Void> gone() {
        return ResponseEntity.status(HttpStatus.GONE).build();
    }
}
