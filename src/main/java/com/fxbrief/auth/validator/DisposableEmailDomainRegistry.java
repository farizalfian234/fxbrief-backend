package com.fxbrief.auth.validator;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class DisposableEmailDomainRegistry {

    private static final String RESOURCE_PATH = "disposable-domains.txt";

    private Set<String> domains = Set.of();

    @PostConstruct
    void load() {
        Set<String> loaded = new HashSet<>();
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toLowerCase();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    loaded.add(trimmed);
                }
            }
        } catch (IOException e) {
            log.error("Failed to load disposable email domain list from {}", RESOURCE_PATH, e);
        }
        this.domains = Set.copyOf(loaded);
        log.info("Loaded {} disposable email domains", domains.size());
    }

    public boolean isDisposable(String email) {
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).toLowerCase();
        return domains.contains(domain);
    }
}
