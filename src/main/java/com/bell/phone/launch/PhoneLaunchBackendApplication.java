package com.bell.phone.launch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PhoneLaunchBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(PhoneLaunchBackendApplication.class, args);
    }
}
