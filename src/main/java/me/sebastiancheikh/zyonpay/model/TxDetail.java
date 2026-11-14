package me.sebastiancheikh.zyonpay.model;

import java.time.Instant;
import java.util.UUID;

/** A full transaction with both parties' names (null = system). */
public record TxDetail(long id, UUID from, UUID to, String fromName, String toName, long amount, TxType type,
                       String server, Instant at, String currency, String reason, boolean reverted) {
}
