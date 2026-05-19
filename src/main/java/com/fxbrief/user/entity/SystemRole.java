package com.fxbrief.user.entity;

public enum SystemRole {

    USER((short) 1),
    ADMIN((short) 2);

    private final short id;

    SystemRole(short id) {
        this.id = id;
    }

    public short getId() {
        return id;
    }
}
