package me.sebastiancheikh.zyonpay.top;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import me.sebastiancheikh.zyonpay.model.AccountBalance;
import me.sebastiancheikh.zyonpay.money.Currency;
import me.sebastiancheikh.zyonpay.money.CurrencyRegistry;
import me.sebastiancheikh.zyonpay.storage.AccountRepository;
import me.sebastiancheikh.zyonpay.storage.StorageException;
import me.sebastiancheikh.zyonpay.sync.RedisBus;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Per-currency rankings and total supply. With Redis, one "leader" server (whoever wins the lock) rebuilds the
 * shared ZSETs from MariaDB every {@code refresh-seconds} and every server copies them into an in-memory snapshot.
 * Without Redis (or if it is down) each server queries MariaDB. Readers (menus, placeholders) only use the
 * snapshot, so they never do I/O.
 */
public final class TopService {

    private record Snapshot(Map<String, List<TopEntry>> tops, Map<String, Long> supplies) {
    }

    private final int size;
    private final int refreshSeconds;
    private final String serverId;
    private final AccountRepository accounts;
    private final RedisBus bus;
    private final CurrencyRegistry currencies;
    private final Logger log;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    private boolean redisWarned;

    /** @param size how many accounts to keep per currency */
    public TopService(int size, int refreshSeconds, String serverId, AccountRepository accounts, RedisBus bus,
                      CurrencyRegistry currencies, Logger log) {
        this.size = size;
        this.refreshSeconds = refreshSeconds;
        this.serverId = serverId;
        this.accounts = accounts;
        this.bus = bus;
        this.currencies = currencies;
        this.log = log;
    }

    public List<TopEntry> snapshot(String currencyId) {
        return snapshot.tops().getOrDefault(currencyId, List.of());
    }

    public Optional<TopEntry> at(String currencyId, int rank) {
        List<TopEntry> current = snapshot(currencyId);
        return rank >= 1 && rank <= current.size() ? Optional.of(current.get(rank - 1)) : Optional.empty();
    }

    public OptionalInt rankOf(String currencyId, UUID uuid) {
        for (TopEntry entry : snapshot(currencyId)) {
            if (entry.uuid().equals(uuid)) {
                return OptionalInt.of(entry.rank());
            }
        }
        return OptionalInt.empty();
    }

    /** Total money in circulation, as of the last refresh. */
    public long supply(String currencyId) {
        return snapshot.supplies().getOrDefault(currencyId, 0L);
    }

    /** Recomputes the snapshot. Run on the storage thread. */
    public void refresh() {
        try {
            snapshot = bus.enabled() ? fromRedisOrDatabase() : fromDatabase();
        } catch (StorageException e) {
            log.warn("Could not update the baltop; keeping the previous one", e);
        }
    }

    private Snapshot fromRedisOrDatabase() {
        try {
            Snapshot result = bus.execute(this::leadAndRead);
            redisWarned = false;
            return result;
        } catch (RedisException e) {
            if (!redisWarned) {
                log.warn("Redis is not responding; computing the baltop from MariaDB: {}", e.getMessage());
                redisWarned = true;
            }
            return fromDatabase();
        }
    }

    private Snapshot leadAndRead(RedisCommands<String, String> redis) {
        long lockSeconds = Math.max(1, refreshSeconds - 1L);
        if (redis.set(bus.key("top:lock"), serverId, SetArgs.Builder.nx().ex(lockSeconds)) != null) {
            for (Currency currency : currencies.all()) {
                rebuild(redis, currency);
            }
        }
        Map<String, List<TopEntry>> tops = new HashMap<>();
        Map<String, Long> supplies = new HashMap<>();
        for (Currency currency : currencies.all()) {
            tops.put(currency.id(), read(redis, currency));
            String supply = redis.get(bus.key("supply:" + currency.id()));
            supplies.put(currency.id(), supply == null ? 0L : Long.parseLong(supply));
        }
        return new Snapshot(tops, supplies);
    }

    /** Writes to a temp key and renames it, so readers never see a half-built ZSET. */
    private void rebuild(RedisCommands<String, String> redis, Currency currency) {
        List<AccountBalance> rows = accounts.top(currency.id(), size);
        String key = bus.key("top:" + currency.id());
        String tmp = key + ":tmp";
        redis.del(tmp);
        if (rows.isEmpty()) {
            redis.del(key);
        } else {
            Object[] scoresAndMembers = new Object[rows.size() * 2];
            for (int i = 0; i < rows.size(); i++) {
                scoresAndMembers[i * 2] = (double) rows.get(i).balance();
                scoresAndMembers[i * 2 + 1] = member(rows.get(i));
            }
            redis.zadd(tmp, scoresAndMembers);
            redis.rename(tmp, key);
        }
        redis.set(bus.key("supply:" + currency.id()), String.valueOf(accounts.supply(currency.id())));
    }

    private List<TopEntry> read(RedisCommands<String, String> redis, Currency currency) {
        List<ScoredValue<String>> values = redis.zrevrangeWithScores(bus.key("top:" + currency.id()), 0, size - 1L);
        List<TopEntry> entries = new ArrayList<>(values.size());
        for (ScoredValue<String> value : values) {
            parse(value.getValue(), entries.size() + 1).ifPresent(entries::add);
        }
        return List.copyOf(entries);
    }

    private Snapshot fromDatabase() {
        Map<String, List<TopEntry>> tops = new HashMap<>();
        Map<String, Long> supplies = new HashMap<>();
        for (Currency currency : currencies.all()) {
            List<TopEntry> entries = new ArrayList<>();
            for (AccountBalance row : accounts.top(currency.id(), size)) {
                entries.add(new TopEntry(entries.size() + 1, row.uuid(), row.name(), row.balance()));
            }
            tops.put(currency.id(), List.copyOf(entries));
            supplies.put(currency.id(), accounts.supply(currency.id()));
        }
        return new Snapshot(tops, supplies);
    }

    /** The exact balance rides in the member because a double score loses precision above 2^53 cents. */
    private static String member(AccountBalance row) {
        return row.uuid() + "|" + row.name() + "|" + row.balance();
    }

    private Optional<TopEntry> parse(String member, int rank) {
        try {
            String[] parts = member.split("\\|", 3);
            return Optional.of(new TopEntry(rank, UUID.fromString(parts[0]), parts[1], Long.parseLong(parts[2])));
        } catch (RuntimeException e) {
            log.warn("Invalid baltop entry in Redis: {}", member);
            return Optional.empty();
        }
    }
}
