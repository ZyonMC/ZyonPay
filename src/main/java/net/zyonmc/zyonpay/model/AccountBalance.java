package net.zyonmc.zyonpay.model;

import java.util.UUID;

/** An account with its balance in one currency (ranking rows). */
public record AccountBalance(UUID uuid, String name, long balance) {
}
