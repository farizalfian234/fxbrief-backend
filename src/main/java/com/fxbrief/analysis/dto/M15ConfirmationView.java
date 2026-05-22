package com.fxbrief.analysis.dto;

public record M15ConfirmationView(
        boolean confirmed,
        boolean liquiditySweep,
        boolean m15Bos,
        boolean engulfingDisplacement,
        boolean chochAlignedWithHtf
) {

    public static M15ConfirmationView none() {
        return new M15ConfirmationView(false, false, false, false, false);
    }
}
