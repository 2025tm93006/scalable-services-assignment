package com.bits.secure.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.bits.secure")
public class ScannerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScannerApplication.class, args);
    }
}
