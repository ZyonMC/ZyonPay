package me.sebastiancheikh.zyonpay.economy;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Accounts of the players connected to this server. */
public final class AccountCache {

    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();

    public Account get(UUID uuid) {
        return accounts.get(uuid);
    }

    public void put(Account account) {
        accounts.put(account.uuid(), account);
    }

    public void remove(UUID uuid) {
        accounts.remove(uuid);
    }

    public Collection<Account> all() {
        return accounts.values();
    }

    /** Case-insensitive scan; only online players are cached, so it stays small. */
    public Optional<Account> findByName(String name) {
        return accounts.values().stream().filter(a -> a.name().equalsIgnoreCase(name)).findFirst();
    }
}
