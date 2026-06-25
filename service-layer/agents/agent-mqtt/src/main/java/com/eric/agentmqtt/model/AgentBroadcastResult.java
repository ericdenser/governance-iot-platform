package com.eric.agentmqtt.model;

import java.util.List;

public record AgentBroadcastResult(
    String command,
    List<String> publishedTo,
    List<String> failed
) {}