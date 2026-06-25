package com.eric.governanceApi.governanceApi.model.response;

import java.util.List;

public record CommandResultResponseDTO( 
     // Resultado do Agent
    String command,
    List<String> publishedTo,
    List<String> failed,
    List<String> skipped
)
{}
