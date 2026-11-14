package me.sebastiancheikh.zyonpay.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Everything loaded for an account when a player joins: balances per currency and the players they block. */
public record LoadedAccount(UUID uuid, String name, boolean acceptsPayments, Map<String, WalletRow> wallets,
                            Set<UUID> blocked) {
}
