package com.fxbrief.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        List<FieldViolation> fieldErrors
) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError of(String code, String message, List<FieldViolation> fieldErrors) {
        return new ApiError(code, message, fieldErrors);
    }

    public record FieldViolation(String field, String message) {}
}
