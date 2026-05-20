package com.fxbrief.subscription.entity;

public enum PlanCode {

    FREE((short) 1, "FREE"),
    BASIC((short) 2, "BASIC"),
    PREMIUM((short) 3, "PREMIUM");

    private final short id;
    private final String code;

    PlanCode(short id, String code) {
        this.id = id;
        this.code = code;
    }

    public short getId() {
        return id;
    }

    public String getCode() {
        return code;
    }
}
