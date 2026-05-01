package com.hivemem.summarize;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SummarizerConfig {

    @Bean
    public NeedsSummaryDecider needsSummaryDecider(SummarizerProperties props) {
        return new NeedsSummaryDecider(props.getSummaryThresholdChars());
    }
}
