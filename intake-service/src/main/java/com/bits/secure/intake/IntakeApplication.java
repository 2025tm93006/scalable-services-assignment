package com.bits.secure.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.bits.secure")
public class IntakeApplication {
    public static void main(String[] args) {
        SpringApplication.run(IntakeApplication.class, args);
    }
}
