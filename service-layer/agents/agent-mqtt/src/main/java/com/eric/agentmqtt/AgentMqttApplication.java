package com.eric.agentmqtt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentMqttApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentMqttApplication.class, args);
	}
}
