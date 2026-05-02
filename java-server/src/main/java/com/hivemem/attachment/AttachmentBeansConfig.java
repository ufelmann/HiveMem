package com.hivemem.attachment;

import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AttachmentBeansConfig {

    @Bean
    public VisionBudgetTracker visionBudgetTracker(DSLContext dsl, AttachmentProperties props) {
        return new VisionBudgetTracker(dsl, props.getVisionDailyBudgetUsd());
    }
}
