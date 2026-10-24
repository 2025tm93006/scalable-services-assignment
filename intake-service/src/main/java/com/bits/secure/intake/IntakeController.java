package com.bits.secure.intake;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class IntakeController {
    private final IntakeService service;

    public IntakeController(IntakeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IntakeResponse receive(@RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody IntakeRequest request) {
        return service.receive(key, request);
    }

    @GetMapping("/{id}")
    public IntakeResponse find(@PathVariable UUID id) {
        return service.find(id);
    }

    // Read-only database count for the assignment demonstration.
    @GetMapping("/_evidence/idempotency/{key}")
    public IntakeService.IdempotencyEvidence evidence(@PathVariable String key) {
        return service.evidence(key);
    }
}
