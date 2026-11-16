package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.config.Settings.PaySettings;
import net.zyonmc.zyonpay.config.Settings.SoundSetting;
import net.zyonmc.zyonpay.money.Currency;
import org.bukkit.entity.Player;

/** Chat message and sounds shown around a payment. Call from the main thread. */
public final class PayNotifier {

    private final Messages messages;
    private final PaySettings settings;

    public PayNotifier(Messages messages, PaySettings settings) {
        this.messages = messages;
        this.settings = settings;
    }

    /** Tells the recipient they got paid ({@code pay.notify-recipient} controls the message, not the sound). */
    public void received(Player recipient, String fromName, Currency currency, long amount) {
        if (settings.notifyRecipient()) {
            messages.send(recipient, "pay.received", Messages.tag("player", fromName),
                    Messages.tag("amount", currency.format(amount)));
        }
        play(recipient, settings.receivedSound());
    }

    public void sent(Player sender) {
        play(sender, settings.sentSound());
    }

    private static void play(Player player, SoundSetting sound) {
        if (sound != null) {
            player.playSound(player.getLocation(), sound.name(), sound.volume(), sound.pitch());
        }
    }
}
