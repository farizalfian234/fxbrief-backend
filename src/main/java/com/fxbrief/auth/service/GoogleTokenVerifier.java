package com.fxbrief.auth.service;

import com.fxbrief.auth.security.GoogleOAuthProperties;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Slf4j
@Service
public class GoogleTokenVerifier {

    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifier(GoogleOAuthProperties properties) {
        List<String> audiences = properties.clientIds();
        if (audiences == null || audiences.isEmpty() || audiences.stream().allMatch(String::isBlank)) {
            throw new IllegalStateException(
                    "fxbrief.oauth.google.client-ids must contain at least one Google OAuth client id");
        }
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(audiences)
                .build();
    }

    public VerifiedGoogleIdentity verify(String idToken) {
        GoogleIdToken parsed;
        try {
            parsed = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException ex) {
            log.warn("Google ID token verification failed: {}", ex.getMessage());
            throw invalidToken();
        }
        if (parsed == null) {
            throw invalidToken();
        }

        GoogleIdToken.Payload payload = parsed.getPayload();
        Boolean emailVerified = payload.getEmailVerified();
        String email = payload.getEmail();
        String subject = payload.getSubject();

        if (subject == null || subject.isBlank() || email == null || email.isBlank()
                || !Boolean.TRUE.equals(emailVerified)) {
            throw invalidToken();
        }

        String name = (String) payload.get("name");
        return new VerifiedGoogleIdentity(subject, email.trim().toLowerCase(), name);
    }

    private DomainException invalidToken() {
        return new DomainException(
                ErrorCodes.INVALID_GOOGLE_TOKEN,
                HttpStatus.BAD_REQUEST,
                "Google ID token is invalid");
    }

    public record VerifiedGoogleIdentity(String subject, String email, String name) {}
}
