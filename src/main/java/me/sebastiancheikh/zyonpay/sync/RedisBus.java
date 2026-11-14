package me.sebastiancheikh.zyonpay.sync;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import me.sebastiancheikh.zyonpay.config.Settings.RedisSettings;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Pub/sub between servers and Redis access (Lettuce). With Redis disabled everything is a no-op and
 * {@link #enabled()} is false.
 * <p>Messages (fields separated by {@code |}): {@code REFRESH|origin|uuid|currency} ({@code *} = all),
 * {@code PAID|origin|uuid|payer|cents|currency} and {@code RESYNC|origin} (reload every cached account).
 * Each server ignores the messages it published itself.
 * <p>Lettuce reconnects and resubscribes on its own; commands are rejected while disconnected so callers
 * (the baltop, for example) can fall back to MariaDB without waiting.
 */
public final class RedisBus implements AutoCloseable {

    /** Currency value meaning "all". */
    public static final String ALL = "*";

    public interface Listener {
        /** Another server changed {@code uuid}'s balance in {@code currency} ({@link #ALL} = every currency). */
        void onRefresh(UUID uuid, String currency);

        /** Another server paid {@code to}. */
        void onPaid(UUID to, String fromName, long cents, String currency);

        /** Another server changed data in bulk (backup restore): reload everything cached. */
        void onResync();
    }

    private static final int RECONNECT_DELAY_MS = 3000;

    private final RedisSettings settings;
    private final String serverId;
    private final Logger log;
    private final boolean debug;
    private final String channel;
    private final RedisClient client;
    private final ExecutorService publisher;
    private volatile boolean running;
    private StatefulRedisConnection<String, String> connection;
    private volatile StatefulRedisPubSubConnection<String, String> subscription;
    private Thread subscriberThread;

    public RedisBus(RedisSettings settings, String serverId, boolean debug, Logger log) {
        this.debug = debug;
        this.settings = settings;
        this.serverId = serverId;
        this.log = log;
        this.channel = settings.prefix() + ":bus";
        if (settings.enabled()) {
            Duration timeout = Duration.ofMillis(settings.timeoutMs());
            RedisURI.Builder uri = RedisURI.builder()
                    .withHost(settings.host())
                    .withPort(settings.port())
                    .withDatabase(settings.database())
                    .withSsl(settings.ssl())
                    .withTimeout(timeout);
            if (!isBlank(settings.password())) {
                if (isBlank(settings.user())) {
                    uri.withPassword(settings.password().toCharArray());
                } else {
                    uri.withAuthentication(settings.user(), settings.password().toCharArray());
                }
            }
            this.client = RedisClient.create(uri.build());
            this.client.setOptions(ClientOptions.builder()
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                    .build());
            this.publisher = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "zyonpay-redis-publisher");
                thread.setDaemon(true);
                return thread;
            });
        } else {
            this.client = null;
            this.publisher = null;
        }
    }

    public boolean enabled() {
        return client != null;
    }

    /** Redis key with the network prefix. */
    public String key(String name) {
        return settings.prefix() + ":" + name;
    }

    public void start(Listener listener) {
        if (!enabled()) {
            return;
        }
        running = true;
        subscriberThread = new Thread(() -> subscribe(listener), "zyonpay-redis-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void publishRefresh(UUID uuid, String currency) {
        publish("REFRESH|" + serverId + "|" + uuid + "|" + currency);
    }

    public void publishRefresh(UUID uuid) {
        publishRefresh(uuid, ALL);
    }

    public void publishPaid(UUID to, String fromName, long cents, String currency) {
        publish("PAID|" + serverId + "|" + to + "|" + fromName + "|" + cents + "|" + currency);
    }

    public void publishResync() {
        publish("RESYNC|" + serverId);
    }

    /** Runs a Redis operation. Throws {@link RedisException} if Redis does not answer. */
    public <T> T execute(Function<RedisCommands<String, String>, T> operation) {
        if (!enabled()) {
            throw new IllegalStateException("Redis is disabled");
        }
        return operation.apply(commands());
    }

    /** Shared connection (Lettuce is thread-safe for non-MULTI, non-blocking commands), created on first use. */
    private synchronized RedisCommands<String, String> commands() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection.sync();
    }

    private void publish(String payload) {
        if (!enabled()) {
            return;
        }
        publisher.execute(() -> {
            try {
                commands().publish(channel, payload);
                if (debug) {
                    log.info("[debug] Redis -> {}", payload);
                }
            } catch (RedisException e) {
                log.warn("Could not publish to Redis: {}", e.getMessage());
            }
        });
    }

    /** Retries until the first subscription succeeds; after that Lettuce reconnects and resubscribes. */
    private void subscribe(Listener listener) {
        while (running) {
            try {
                StatefulRedisPubSubConnection<String, String> pubSub = client.connectPubSub();
                pubSub.addListener(new RedisPubSubAdapter<>() {
                    @Override
                    public void message(String ch, String message) {
                        handle(listener, message);
                    }
                });
                pubSub.sync().subscribe(channel);
                subscription = pubSub;
                return;
            } catch (RedisException e) {
                if (running) {
                    log.warn("Could not subscribe to Redis, retrying in {} s: {}", RECONNECT_DELAY_MS / 1000,
                            e.getMessage());
                }
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handle(Listener listener, String message) {
        if (debug) {
            log.info("[debug] Redis <- {}", message);
        }
        try {
            String[] parts = message.split("\\|", 6);
            if (parts.length < 2 || parts[1].equals(serverId)) {
                return;
            }
            switch (parts[0]) {
                case "RESYNC" -> listener.onResync();
                case "REFRESH" -> {
                    if (parts.length >= 3) {
                        listener.onRefresh(UUID.fromString(parts[2]), parts.length >= 4 ? parts[3] : ALL);
                    }
                }
                case "PAID" -> {
                    if (parts.length == 6) {
                        listener.onPaid(UUID.fromString(parts[2]), parts[3], Long.parseLong(parts[4]), parts[5]);
                    }
                }
                default -> log.debug("Unknown Redis message: {}", message);
            }
        } catch (RuntimeException e) {
            log.warn("Invalid Redis message '{}'", message, e);
        }
    }

    @Override
    public void close() {
        if (!enabled()) {
            return;
        }
        running = false;
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        publisher.shutdown();
        try {
            publisher.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        StatefulRedisPubSubConnection<String, String> current = subscription;
        if (current != null) {
            current.close();
        }
        synchronized (this) {
            if (connection != null) {
                connection.close();
            }
        }
        client.shutdown(0, 1, TimeUnit.SECONDS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
