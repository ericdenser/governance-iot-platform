package com.eric.eventhandler.event_handler.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiError {
    private String code;
    private String message;
}
