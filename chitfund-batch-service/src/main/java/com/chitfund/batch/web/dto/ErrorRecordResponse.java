package com.chitfund.batch.web.dto;

import com.chitfund.batch.domain.BatchJobErrorRecord;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ErrorRecordResponse(
        Long errorId,
        String jobName,
        Long jobExecutionId,
        LocalDate businessDate,
        String recordIdentifier,
        String exceptionClass,
        String exceptionMessage,
        String replayStatus,
        OffsetDateTime createdAt
) {
    public static ErrorRecordResponse from(BatchJobErrorRecord e) {
        return new ErrorRecordResponse(
                e.getErrorId(), e.getJobName(), e.getJobExecutionId(), e.getBusinessDate(),
                e.getRecordIdentifier(), e.getExceptionClass(), e.getExceptionMessage(),
                e.getReplayStatus().name(), e.getCreatedAt()
        );
    }
}
