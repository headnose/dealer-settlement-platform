package io.github.headnose.dsp.settlement.model;

public enum AdjustmentType {
    DEALER_HOLDBACK,   // amount held back from the dealer until a condition is met
    DOC_FEE,           // documentation fee
    TITLE_FEE,         // title processing fee
    DISPUTED_ITEM,     // amount withheld while a dispute is resolved
    OTHER
}