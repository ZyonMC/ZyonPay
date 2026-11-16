package net.zyonmc.zyonpay.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A money movement. {@code from} or {@code to} is null when the other party is the system.
 * {@code reason} is free text (the /eco reason, the plugin that called Vault...) or null.
 */
public record Transaction(UUID from, UUID to, long amount, TxType type, String server, Instant at, String currency,
                          String reason) {
}
