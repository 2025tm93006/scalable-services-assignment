package com.bits.secure.scanner;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scans")
public class ScannerController {
    private final ScannerService service;

    public ScannerController(ScannerService service) {
        this.service = service;
    }

    @PostMapping
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return service.scan(request);
    }

    @GetMapping("/_evidence/summary")
    public ScannerEvidence evidence() {
        return service.evidence();
    }

    @GetMapping("/{fileId}")
    public ScanAttemptResponse find(@PathVariable UUID fileId) {
        return service.find(fileId);
    }
}
