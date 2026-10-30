package com.bits.secure.quarantine;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.bits.secure")
@EnableScheduling
public class QuarantineApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuarantineApplication.class, args);
    }
}
