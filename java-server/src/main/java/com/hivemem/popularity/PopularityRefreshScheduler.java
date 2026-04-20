package com.hivemem.popularity;

import com.hivemem.write.AdminToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PopularityRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(PopularityRefreshScheduler.class);

    private final AdminToolRepository adminToolRepository;

    public PopularityRefreshScheduler(AdminToolRepository adminToolRepository) {
        this.adminToolRepository = adminToolRepository;
    }

    @Scheduled(fixedDelayString = "${hivemem.popularity.refresh-interval:PT1H}")
    public void refresh() {
        try {
            long cellCount = adminToolRepository.refreshPopularity();
            log.info("Popularity materialized view refreshed: {} cells", cellCount);
        } catch (Exception e) {
            log.error("Popularity refresh failed", e);
        }
    }
}
