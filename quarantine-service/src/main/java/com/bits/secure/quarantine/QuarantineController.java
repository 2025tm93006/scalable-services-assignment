package com.bits.secure.quarantine;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quarantine")
public class QuarantineController {
    private final QuarantineService service;

    public QuarantineController(QuarantineService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuarantineResponse create(@Valid @RequestBody QuarantineRequest request) {
        return service.quarantine(request);
    }

    @PostMapping("/{id}/scan")
    public QuarantineResponse scan(@PathVariable UUID id) {
        return service.scan(id);
    }

    @GetMapping("/_evidence/summary")
    public QuarantineEvidence evidence() {
        return service.evidence();
    }

    @GetMapping("/by-file/{fileId}")
    public QuarantineResponse getByFileId(@PathVariable UUID fileId) {
        return service.getByFileId(fileId);
    }

    @GetMapping("/{id}")
    public QuarantineResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}