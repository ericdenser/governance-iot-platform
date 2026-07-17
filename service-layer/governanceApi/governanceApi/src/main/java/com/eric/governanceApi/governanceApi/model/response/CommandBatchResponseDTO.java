package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.Map;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandBatch;

public record CommandBatchResponseDTO(
    String batchId,
    DeviceCommands commandType,
    String targetVersionLabel,
    Instant sentAt,
    String createdByUsername,
    String aggregateStatus,
    long total,
    long success,
    long pending,
    long failed,
    long skipped,
    long notFound
) {

    public static CommandBatchResponseDTO from(CommandBatch batch, Map<CommandStatus, Long> counts) {
        long success = counts.getOrDefault(CommandStatus.COMPLETED_SUCCESS, 0L);
        long pending = counts.getOrDefault(CommandStatus.PENDING, 0L);
        long skipped = counts.getOrDefault(CommandStatus.SKIPPED, 0L);
        long failed  = counts.getOrDefault(CommandStatus.FAILED, 0L)
                     + counts.getOrDefault(CommandStatus.PUBLISH_FAILED, 0L)
                     + counts.getOrDefault(CommandStatus.TIMEOUT, 0L);
        long notFound = (batch.getNotFoundIds() == null || batch.getNotFoundIds().isBlank())
                ? 0 : batch.getNotFoundIds().split(",").length;
        long total = success + pending + skipped + failed + notFound;

        String aggregate;
        if (pending > 0)                                        aggregate = "IN_PROGRESS";
        else if (success > 0 && failed + skipped + notFound == 0) aggregate = "SUCCESS";
        else if (success > 0)                                   aggregate = "PARTIAL";
        else                                                    aggregate = "FAILED";

        return new CommandBatchResponseDTO(
                batch.getBatchId(),
                batch.getCommandType(),
                batch.getTargetVersionLabel(),
                batch.getSentAt(),
                batch.getCreatedByUsername(),
                aggregate,
                total, success, pending, failed, skipped, notFound);
    }
}
