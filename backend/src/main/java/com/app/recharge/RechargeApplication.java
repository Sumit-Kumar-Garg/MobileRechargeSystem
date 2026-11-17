package com.app.recharge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RechargeApplication {
    /** Starts the Spring Boot application. */
    public static void main(String[] args) {
        SpringApplication.run(RechargeApplication.class, args);
    }
}
