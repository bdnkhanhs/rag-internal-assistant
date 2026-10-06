package com.example.rag.config;

import com.example.rag.domain.AppUser;
import com.example.rag.repository.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {
    @Bean
    CommandLineRunner seedUsers(AppUserRepository users, PasswordEncoder encoder) {
        return args -> {
            if (users.findByUsername("admin").isEmpty()) {
                users.save(new AppUser("admin", encoder.encode("Admin@123"), "ADMIN"));
            }
            if (users.findByUsername("internal").isEmpty()) {
                users.save(new AppUser("internal", encoder.encode("User@123"), "USER"));
            }
        };
    }
}
