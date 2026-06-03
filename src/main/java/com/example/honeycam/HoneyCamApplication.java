package com.example.honeycam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HoneyCamApplication {

	public static void main(String[] args) {
		SpringApplication.run(HoneyCamApplication.class, args);
	}

}
