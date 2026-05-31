package com.fxbrief.auth.service;

import com.fxbrief.auth.dto.GoogleLoginRequest;
import com.fxbrief.auth.dto.LoginResponse;
import com.fxbrief.auth.entity.OAuthAccount;
import com.fxbrief.auth.repository.OAuthAccountRepository;
import com.fxbrief.auth.security.AccountProperties;
import com.fxbrief.auth.security.JwtService;
import com.fxbrief.notification.service.NotificationService;
import com.fxbrief.subscription.service.SubscriptionService;
import com.fxbrief.user.entity.Role;
import com.fxbrief.user.entity.SystemRole;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.RoleRepository;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private static final String PROVIDER_GOOGLE = "google";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtService jwtService;
    private final AccountProperties accountProperties;
    private final SubscriptionService subscriptionService;
    private final NotificationService notificationService;

    @Transactional
    public LoginResponse login(GoogleLoginRequest request) {
        GoogleTokenVerifier.VerifiedGoogleIdentity identity = googleTokenVerifier.verify(request.idToken());

        User user = oauthAccountRepository
                .findByProviderAndProviderUserId(PROVIDER_GOOGLE, identity.subject())
                .map(OAuthAccount::getUser)
                .orElseGet(() -> resolveOrCreateUser(identity));

        String roleName = user.getRole().getName();
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getEmail(), roleName);

        boolean deletionPending = user.getDeletionRequestedAt() != null;
        Instant deletionDate = deletionPending
                ? user.getDeletionRequestedAt().plus(Duration.ofDays(accountProperties.deletionGraceDays()))
                : null;

        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt(),
                user.getId(),
                user.getEmail(),
                user.getName(),
                roleName,
                deletionPending,
                deletionDate);
    }

    private User resolveOrCreateUser(GoogleTokenVerifier.VerifiedGoogleIdentity identity) {
        Optional<User> existing = userRepository.findByEmail(identity.email());
        if (existing.isPresent()) {
            User user = existing.get();
            linkGoogleAccount(user, identity);
            if (!user.isActive()) {
                user.setActive(true);
            }
            log.info("Linked Google account to existing user id={} email={}", user.getId(), user.getEmail());
            return user;
        }
        return createGoogleUser(identity);
    }

    private User createGoogleUser(GoogleTokenVerifier.VerifiedGoogleIdentity identity) {
        Role userRole = roleRepository.getReferenceById(SystemRole.USER.getId());

        User user = new User();
        user.setEmail(identity.email());
        user.setName(resolveName(identity));
        user.setRole(userRole);
        user.setActive(true);
        user.setHasEverPaid(false);
        userRepository.save(user);

        linkGoogleAccount(user, identity);
        subscriptionService.provisionFreePlan(user);

        log.info("Created Google-registered user id={} email={}", user.getId(), user.getEmail());

        notificationService.sendWelcomeEmail(user.getEmail(), user.getName());

        return user;
    }

    private void linkGoogleAccount(User user, GoogleTokenVerifier.VerifiedGoogleIdentity identity) {
        OAuthAccount link = new OAuthAccount();
        link.setUser(user);
        link.setProvider(PROVIDER_GOOGLE);
        link.setProviderUserId(identity.subject());
        link.setEmail(identity.email());
        oauthAccountRepository.save(link);
    }

    private String resolveName(GoogleTokenVerifier.VerifiedGoogleIdentity identity) {
        String name = identity.name();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String email = identity.email();
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
