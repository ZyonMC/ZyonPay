package net.zyonmc.zyonpay.menu;

/** A data source could not prepare the menu; {@code messageKey} is the messages.yml key shown to the player. */
public final class MenuLoadException extends RuntimeException {

    private final String messageKey;

    public MenuLoadException(String messageKey) {
        super(messageKey, null, false, false);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
