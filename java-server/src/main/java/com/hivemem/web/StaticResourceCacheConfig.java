package com.hivemem.web;

import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sends a {@code Cache-Control} header on static resources. Without one, a response that
 * only carries {@code Last-Modified} is treated by browsers as heuristically fresh, so the
 * SPA shell can be served straight from the HTTP cache for a long stretch with no network
 * round-trip at all. Since this app sits behind Cloudflare Access, a browser that never
 * issues that navigation never lets Access challenge an expired session, and the app
 * dead-ends on its error screen instead of re-authenticating — the same failure class a
 * service-worker precache can cause, reproduced here with no service worker involved.
 *
 * <p>Two distinct policies:
 * <ul>
 *   <li>The SPA shell ({@code /index.html}, reached directly or via {@link SpaController}'s
 *   internal forward for {@code /} and every deep link) and {@code /sw.js} are {@code
 *   no-cache}: stored, but always revalidated against the server (conditional requests
 *   still 304 via the {@code Last-Modified} Spring's resource handler already sets — it does
 *   not generate an {@code ETag} by default, so {@code Last-Modified} is the only
 *   revalidation signal here), so a stale shell is never served from cache.
 *   <li>Content-hashed build output under {@code /assets/} is cached hard for a year and
 *   marked {@code immutable}: those filenames change on every content change, which is the
 *   entire point of content hashing, so long-lived caching is safe.
 * </ul>
 *
 * <p>These handlers register more specific Ant patterns ({@code /index.html}, {@code
 * /sw.js}, {@code /assets/**}) than Spring Boot's auto-configured default ({@code /**}), so
 * they take precedence for matching requests without displacing the default handler for
 * everything else under {@code classpath:/static/} (favicon, manifest, PWA icons, etc.),
 * which keeps serving exactly as before — no {@code Cache-Control} header, same as today.
 */
@Configuration
public class StaticResourceCacheConfig implements WebMvcConfigurer {

    // Hardcoded rather than reading spring.web.resources.static-locations: Boot's default
    // resource handler honours that property and searches four locations, but this class
    // only needs the one the build actually publishes to (the Dockerfile bakes the built
    // knowledge-ui output into classpath:/static/). If that property is ever overridden,
    // these more-specific handlers keep pointing at classpath:/static/ while the default
    // /** handler moves — /index.html, /sw.js and /assets/** would then silently 404 here
    // even though everything else keeps working.
    private static final String STATIC_CLASSPATH_LOCATION = "classpath:/static/";
    private static final long ONE_YEAR_DAYS = 365;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html", "/sw.js")
                .addResourceLocations(STATIC_CLASSPATH_LOCATION)
                .setCacheControl(CacheControl.noCache());

        registry.addResourceHandler("/assets/**")
                .addResourceLocations(STATIC_CLASSPATH_LOCATION + "assets/")
                .setCacheControl(CacheControl.maxAge(ONE_YEAR_DAYS, TimeUnit.DAYS)
                        .cachePublic()
                        .immutable());
    }
}
