package com.eric.datalogger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DataloggerApplication {

	public static void main(String[] args) {
		SpringApplication.run(DataloggerApplication.class, args);
	}

}
