package com.eric.governanceApi.governanceApi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GovernanceApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GovernanceApiApplication.class, args);
	}

}
