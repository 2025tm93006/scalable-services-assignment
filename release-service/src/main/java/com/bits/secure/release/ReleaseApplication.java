package com.bits.secure.release;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.bits.secure")
public class ReleaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReleaseApplication.class, args);
    }
}
