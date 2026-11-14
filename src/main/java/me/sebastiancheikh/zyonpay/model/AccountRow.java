package me.sebastiancheikh.zyonpay.model;

import java.util.UUID;

/** Identity of an account. Balances live apart, per currency ({@link WalletRow}). */
public record AccountRow(UUID uuid, String name, boolean acceptsPayments) {
}
