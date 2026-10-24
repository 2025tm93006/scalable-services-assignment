package com.bits.secure.common;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.Map;
@RestController
public class HealthController {
    private final String service;
    public HealthController(@Value("${spring.application.name:secure-file-service}") String service) { this.service = service; }
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","UP","service",service,"timestamp", Instant.now());}
}
