package com.ewalab.modbillder;

public enum Region {
    EU,
    NA,
    RU,
    CUSTOM,
    NONE;

    public String displayName() {
        return name();
    }
}
