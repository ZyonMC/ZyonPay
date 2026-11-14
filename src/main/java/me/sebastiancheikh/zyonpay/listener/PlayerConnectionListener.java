package me.sebastiancheikh.zyonpay.listener;

import me.sebastiancheikh.zyonpay.config.Messages;
import me.sebastiancheikh.zyonpay.economy.EconomyService;
import me.sebastiancheikh.zyonpay.storage.StorageException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

/**
 * Loads the account before the player joins (so it is cached from the first tick) and unloads it on quit.
 * If the database fails the player is kept out: better than playing with a balance that cannot be saved.
 */
public final class PlayerConnectionListener implements Listener {

    private final EconomyService economy;
    private final Messages messages;
    private final Logger log;

    public PlayerConnectionListener(EconomyService economy, Messages messages, Logger log) {
        this.economy = economy;
        this.messages = messages;
        this.log = log;
    }

    // MONITOR: only load if no other plugin has already denied the login.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            economy.load(event.getUniqueId(), event.getName());
        } catch (StorageException e) {
            log.error("Could not load the account of {}", event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("login-database-error"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        economy.unload(event.getPlayer().getUniqueId());
    }
}
