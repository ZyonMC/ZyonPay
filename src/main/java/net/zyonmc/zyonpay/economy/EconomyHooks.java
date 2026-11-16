package net.zyonmc.zyonpay.economy;

import net.zyonmc.zyonpay.model.Transaction;
import net.zyonmc.zyonpay.money.Currency;

import java.util.UUID;

/**
 * Callbacks the economy invokes around money operations. They run on whichever thread performs the operation
 * (main or storage), so implementations must be thread-safe and fast.
 */
public interface EconomyHooks {

    EconomyHooks NONE = new EconomyHooks() {
    };

    /** Called before a payment moves any money; return false to cancel it. {@code tax} is part of {@code amount}. */
    default boolean allowPay(UUID from, UUID to, Currency currency, long amount, long tax) {
        return true;
    }

    /** Called after an operation succeeded. */
    default void completed(Transaction transaction) {
    }
}
