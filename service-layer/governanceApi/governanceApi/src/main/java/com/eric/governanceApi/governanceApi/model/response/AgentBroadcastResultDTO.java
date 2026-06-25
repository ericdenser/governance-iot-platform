package com.eric.governanceApi.governanceApi.model.response;

import java.util.List;

public record AgentBroadcastResultDTO(
    List<String> publishedTo,
    List<String> failed
) {
    
}
