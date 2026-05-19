package com.fxbrief.auth.service;

import com.fxbrief.auth.dto.MessageResponse;
import com.fxbrief.auth.dto.VerifyEmailRequest;
import com.fxbrief.auth.entity.EmailVerificationToken;
import com.fxbrief.auth.repository.EmailVerificationTokenRepository;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public MessageResponse verify(VerifyEmailRequest request) {
        EmailVerificationToken token = tokenRepository.findByToken(request.token())
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.INVALID_TOKEN,
                        HttpStatus.BAD_REQUEST,
                        "Verification token is invalid"));

        Instant now = Instant.now();

        if (token.isUsed()) {
            throw new DomainException(
                    ErrorCodes.TOKEN_ALREADY_USED,
                    HttpStatus.BAD_REQUEST,
                    "Verification token has already been used");
        }

        if (token.isExpired(now)) {
            throw new DomainException(
                    ErrorCodes.TOKEN_EXPIRED,
                    HttpStatus.BAD_REQUEST,
                    "Verification token has expired");
        }

        User user = token.getUser();
        user.setActive(true);
        userRepository.save(user);

        token.setUsedAt(now);
        tokenRepository.save(token);

        return new MessageResponse("Email verified. Your account is now active.");
    }
}
