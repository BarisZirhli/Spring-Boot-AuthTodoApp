package com.example.authcrud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class AuthCrudApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthCrudApplication.class, args);
    }
}
