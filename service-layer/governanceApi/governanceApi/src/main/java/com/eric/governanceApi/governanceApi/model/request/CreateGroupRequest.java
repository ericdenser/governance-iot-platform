package com.eric.governanceApi.governanceApi.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGroupRequest(
        
        @NotBlank @Size(max = 50) 
        String name,

        @Size(max = 500) 
        String description
) {}
