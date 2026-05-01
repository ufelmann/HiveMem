package com.hivemem.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.extraction")
public class ExtractionProperties {

    /** Master switch — defaults to true; in practice extraction only runs when summarize.enabled also true. */
    private boolean enabled = true;
    private String defaultFallbackType = "other";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getDefaultFallbackType() { return defaultFallbackType; }
    public void setDefaultFallbackType(String v) { this.defaultFallbackType = v; }
}
