package com.fxbrief.report.service;

import com.fxbrief.analysis.entity.Pair;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.dto.GenerateReportRequest;
import com.fxbrief.report.dto.PreferenceSnapshot;
import com.fxbrief.report.entity.PreferenceType;
import com.fxbrief.report.entity.PreferenceValue;
import com.fxbrief.report.entity.UserPreference;
import com.fxbrief.report.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves the preference snapshot to use for one generation call.
 *
 * <p>Resolution priority (Addition 3 spec):
 * <ol>
 *   <li>Request body override (both {@code preferenceType} and
 *       {@code preferenceValue} non-null) — use this, validate, and do
 *       <em>not</em> mutate {@code user_preferences}.</li>
 *   <li>Persisted {@code user_preferences} row for this user — use this.</li>
 *   <li>Neither — return {@link Optional#empty()}; the report flow then
 *       skips compatibility scoring entirely.</li>
 * </ol>
 *
 * <p>A partial override (one field set, the other blank) is rejected as a
 * validation error rather than silently ignored — see {@link GenerateReportRequest}.
 *
 * <p>Invalid persisted preferences (e.g. a stale row referencing an enum
 * value that has since been removed) degrade to "no preference" with a
 * WARN log rather than throwing — the report should still render.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreferenceResolver {

    private final UserPreferenceRepository userPreferenceRepository;

    @Transactional(readOnly = true)
    public Optional<PreferenceSnapshot> resolve(Long userId, GenerateReportRequest request) {
        if (request != null && request.isPartial()) {
            throw new DomainException(
                    ErrorCodes.VALIDATION_FAILED,
                    HttpStatus.BAD_REQUEST,
                    "preferenceType and preferenceValue must both be provided or both omitted");
        }

        if (request != null && request.hasOverride()) {
            validate(request.preferenceType(), request.preferenceValue(), /* throwing */ true);
            return Optional.of(new PreferenceSnapshot(
                    request.preferenceType(), request.preferenceValue()));
        }

        Optional<UserPreference> persisted = userPreferenceRepository.findByUserId(userId);
        if (persisted.isEmpty()) {
            return Optional.empty();
        }

        UserPreference row = persisted.get();
        if (!validate(row.getPreferenceType(), row.getPreferenceValue(), /* throwing */ false)) {
            log.warn("Skipping persisted preference for user={} — invalid (type={}, value={})",
                    userId, row.getPreferenceType(), row.getPreferenceValue());
            return Optional.empty();
        }

        return Optional.of(new PreferenceSnapshot(
                row.getPreferenceType(), row.getPreferenceValue()));
    }

    /**
     * Validates that the (type, value) pair is recognised.
     *
     * @param throwingOnInvalid when true, throws {@link DomainException} with
     *         {@code VALIDATION_FAILED}; when false, returns {@code false}
     *         silently so the caller can log and degrade.
     */
    private boolean validate(PreferenceType type, String value, boolean throwingOnInvalid) {
        boolean ok = switch (type) {
            case TRADING_STYLE, PREFERRED_SESSION, RISK_PROFILE ->
                    PreferenceValue.resolve(type, value).isPresent();
            case FAVORITE_PAIR ->
                    Pair.fromSymbol(value).isPresent();
        };

        if (!ok && throwingOnInvalid) {
            throw new DomainException(
                    ErrorCodes.VALIDATION_FAILED,
                    HttpStatus.BAD_REQUEST,
                    "Invalid preferenceValue '" + value + "' for preferenceType " + type);
        }
        return ok;
    }
}
