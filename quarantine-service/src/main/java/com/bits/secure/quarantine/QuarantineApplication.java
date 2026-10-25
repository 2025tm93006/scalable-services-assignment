package com.bits.secure.quarantine;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication(scanBasePackages = "com.bits.secure")
@RestController
public class QuarantineApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuarantineApplication.class, args);
    }

    @GetMapping("/quarantine")
    public Map<String, Object> quarantineStatus() {
        return Map.of(
                "service", "quarantine-service",
                "status", "UP",
                "mode", "standby"
        );
    }
}
