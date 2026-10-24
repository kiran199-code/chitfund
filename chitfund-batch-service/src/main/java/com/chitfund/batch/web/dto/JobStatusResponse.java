package com.chitfund.batch.web.dto;

import java.time.LocalDateTime;

public record JobStatusResponse(
        String jobName,
        String jobCategory,
        boolean enabled,
        boolean running,
        String lastStatus,
        String lastExitCode,
        LocalDateTime lastRunStartTime,
        LocalDateTime nextFireTime
) {
}
