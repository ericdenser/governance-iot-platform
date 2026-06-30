package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;

public record CommandRecordResponseDTO(
    String commandId,
    DeviceCommands commandType,
    CommandStatus status,
    Instant sentAt,
    String payload,
    Instant completedAt,
    String errorMessage,
    String deviceId,
    String deviceName,
    String createdByActorId,
    String createdByUsername
) {

    public static CommandRecordResponseDTO from(CommandRecord command) {
        return new CommandRecordResponseDTO(
            command.getCommandId(),
            command.getCommandType(),
            command.getStatus(),
            command.getSentAt(),
            command.getPayload(),
            command.getCompletedAt(),
            command.getErrorMessage(),
            command.getTargetDevice() != null ? command.getTargetDevice().getDeviceId() : null,
            command.getTargetDevice() != null ? command.getTargetDevice().getName() : null,
            command.getCreatedByActorId(),
            command.getCreatedByUsername()
        );
    }
}
