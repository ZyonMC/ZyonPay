package net.zyonmc.zyonpay.economy;

/** Outcome of a money operation. */
public enum Result {
    SUCCESS,
    INSUFFICIENT_FUNDS,
    MAX_BALANCE,
    NOT_FOUND,
    SELF,
    PAYMENTS_DISABLED,
    BLOCKED,
    TARGET_OFFLINE,
    TOO_SMALL,
    CANCELLED,
    ALREADY_REVERTED,
    ERROR
}
