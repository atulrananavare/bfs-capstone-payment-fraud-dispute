package com.example.fraudcase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudCaseAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudCaseAgentApplication.class, args);
    }
}
