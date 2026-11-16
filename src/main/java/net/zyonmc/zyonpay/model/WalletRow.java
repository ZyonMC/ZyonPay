package net.zyonmc.zyonpay.model;

/**
 * An account's balance in one currency.
 *
 * @param maxBalance own cap ({@code /eco setmax}) or {@link #NO_MAX} to use the currency's
 */
public record WalletRow(long balance, long maxBalance) {

    public static final long NO_MAX = -1;
}
