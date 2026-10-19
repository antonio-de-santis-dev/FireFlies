package it.fireflies;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FirefliesApplication {

    public static void main(String[] args) {
        SpringApplication.run(FirefliesApplication.class, args);
    }
}
