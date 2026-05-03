package com.eric.agent.model;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DataDTO(

    @NotBlank(message = "measurement cannot be blank")
    String measurement,

    @NotBlank(message = "tag cannot be blank")
    String tag,

    @NotNull(message = "data cannot be null")
    Double data

) {
    
}
