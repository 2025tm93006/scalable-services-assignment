package com.bits.secure.release;

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
@RequestMapping("/api/v1/releases")
public class ReleaseController {
    private final ReleaseService service;

    public ReleaseController(ReleaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseResponse release(@Valid @RequestBody ReleaseRequest request) {
        return service.release(request);
    }

    @GetMapping("/{id}")
    public ReleaseResponse find(@PathVariable UUID id) {
        return service.find(id);
    }
}
