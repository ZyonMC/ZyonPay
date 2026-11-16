package net.zyonmc.zyonpay.top;

import java.util.UUID;

public record TopEntry(int rank, UUID uuid, String name, long balance) {
}
