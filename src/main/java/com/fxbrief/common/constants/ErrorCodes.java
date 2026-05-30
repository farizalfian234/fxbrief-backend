package com.fxbrief.common.constants;

public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

    public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
    public static final String EMAIL_DOMAIN_NOT_ALLOWED = "EMAIL_DOMAIN_NOT_ALLOWED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String ACCOUNT_NOT_VERIFIED = "ACCOUNT_NOT_VERIFIED";
    public static final String ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE";
    public static final String INVALID_TOKEN = "INVALID_TOKEN";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String TOKEN_ALREADY_USED = "TOKEN_ALREADY_USED";
    public static final String PASSWORD_RESET_UNAVAILABLE = "PASSWORD_RESET_UNAVAILABLE";
    public static final String INVALID_GOOGLE_TOKEN = "INVALID_GOOGLE_TOKEN";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String FORBIDDEN = "FORBIDDEN";

    public static final String INVALID_PLAN_FOR_TOP_UP = "INVALID_PLAN_FOR_TOP_UP";

    public static final String MARKET_DATA_UNAVAILABLE = "MARKET_DATA_UNAVAILABLE";
    public static final String NARRATIVE_UNAVAILABLE = "NARRATIVE_UNAVAILABLE";
    public static final String MARKET_DATA_NOT_READY = "MARKET_DATA_NOT_READY";

    public static final String MARKET_CLOSED = "MARKET_CLOSED";
    public static final String DAILY_LIMIT_REACHED = "DAILY_LIMIT_REACHED";
    public static final String NO_REMAINING_REPORTS = "NO_REMAINING_REPORTS";

    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String CANNOT_MODIFY_ADMIN = "CANNOT_MODIFY_ADMIN";
    public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";

    public static final String FEEDBACK_NOT_FOUND = "FEEDBACK_NOT_FOUND";
    public static final String FEEDBACK_ALREADY_REPLIED = "FEEDBACK_ALREADY_REPLIED";

    public static final String WEEKLY_SUMMARY_NOT_FOUND = "WEEKLY_SUMMARY_NOT_FOUND";
    public static final String INVALID_WEEKLY_SUMMARY_STATUS = "INVALID_WEEKLY_SUMMARY_STATUS";
    public static final String WEEKLY_SUMMARY_NOT_PUBLISHABLE = "WEEKLY_SUMMARY_NOT_PUBLISHABLE";

    public static final String ARTICLE_NOT_FOUND = "ARTICLE_NOT_FOUND";
    public static final String INVALID_ARTICLE_STATUS = "INVALID_ARTICLE_STATUS";
    public static final String INVALID_ARTICLE_CATEGORY = "INVALID_ARTICLE_CATEGORY";
    public static final String ARTICLE_SLUG_CONFLICT = "ARTICLE_SLUG_CONFLICT";
    public static final String ARTICLE_DELETE_NOT_ALLOWED = "ARTICLE_DELETE_NOT_ALLOWED";

    private ErrorCodes() {}
}
