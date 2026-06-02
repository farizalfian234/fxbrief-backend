package com.fxbrief.subscription.entity;

public enum PlanCode {

    FREE((short) 1, "FREE", "Free"),
    BASIC((short) 2, "BASIC", "Basic"),
    PREMIUM((short) 3, "PREMIUM", "Premium");

    private final short id;
    private final String code;
    private final String displayName;

    PlanCode(short id, String code, String displayName) {
        this.id = id;
        this.code = code;
        this.displayName = displayName;
    }

    public short getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }
}
