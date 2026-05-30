package com.fxbrief.report.service;

import com.fxbrief.analysis.entity.Pair;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.dto.PreferenceOptionsView;
import com.fxbrief.report.dto.UpdatePreferenceRequest;
import com.fxbrief.report.dto.UserPreferenceView;
import com.fxbrief.report.entity.PreferenceType;
import com.fxbrief.report.entity.PreferenceValue;
import com.fxbrief.report.entity.UserPreference;
import com.fxbrief.report.repository.UserPreferenceRepository;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Read/write surface for a user's single saved market preference, backing the
 * Account page picker. The compatibility-scoring engine that consumes this
 * preference already exists (Addition 3); this service only owns the CRUD over
 * the at-most-one {@code user_preferences} row plus the static options list.
 *
 * <p>The (type, value) pair is validated the same way {@code PreferenceResolver}
 * validates a resolved preference: enum-name match via {@link PreferenceValue}
 * for the three non-pair types, and {@link Pair#fromSymbol(String)} for
 * {@link PreferenceType#FAVORITE_PAIR}.
 */
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserPreferenceView getPreference(Long userId) {
        return userPreferenceRepository.findByUserId(userId)
                .map(p -> UserPreferenceView.of(p.getPreferenceType().name(), p.getPreferenceValue()))
                .orElseGet(UserPreferenceView::none);
    }

    @Transactional(readOnly = true)
    public PreferenceOptionsView getOptions() {
        List<PreferenceOptionsView.TypeOptions> types = Arrays.stream(PreferenceType.values())
                .map(type -> new PreferenceOptionsView.TypeOptions(type.name(), valuesFor(type)))
                .toList();
        return new PreferenceOptionsView(types);
    }

    @Transactional
    public UserPreferenceView setPreference(Long userId, UpdatePreferenceRequest request) {
        PreferenceType type = parseType(request.preferenceType());
        String value = normaliseAndValidateValue(type, request.preferenceValue());

        UserPreference preference = userPreferenceRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserPreference fresh = new UserPreference();
                    fresh.setUser(userRepository.getReferenceById(userId));
                    return fresh;
                });
        preference.setPreferenceType(type);
        preference.setPreferenceValue(value);
        userPreferenceRepository.save(preference);

        return UserPreferenceView.of(type.name(), value);
    }

    @Transactional
    public void clearPreference(Long userId) {
        userPreferenceRepository.findByUserId(userId)
                .ifPresent(userPreferenceRepository::delete);
    }

    private static List<String> valuesFor(PreferenceType type) {
        if (type == PreferenceType.FAVORITE_PAIR) {
            return Arrays.stream(Pair.values()).map(Pair::getSymbol).toList();
        }
        return PreferenceValue.valuesFor(type).stream().map(Enum::name).toList();
    }

    private static PreferenceType parseType(String raw) {
        try {
            return PreferenceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(
                    ErrorCodes.VALIDATION_FAILED,
                    HttpStatus.BAD_REQUEST,
                    "Unknown preferenceType: " + raw);
        }
    }

    private static String normaliseAndValidateValue(PreferenceType type, String rawValue) {
        String value = rawValue.trim();
        if (type == PreferenceType.FAVORITE_PAIR) {
            return Pair.fromSymbol(value)
                    .map(Pair::getSymbol)
                    .orElseThrow(() -> new DomainException(
                            ErrorCodes.VALIDATION_FAILED,
                            HttpStatus.BAD_REQUEST,
                            "Unknown FAVORITE_PAIR value: " + rawValue));
        }
        String normalised = value.toUpperCase();
        return PreferenceValue.resolve(type, normalised)
                .map(Enum::name)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.VALIDATION_FAILED,
                        HttpStatus.BAD_REQUEST,
                        "Value " + rawValue + " is not valid for preferenceType " + type.name()));
    }
}
