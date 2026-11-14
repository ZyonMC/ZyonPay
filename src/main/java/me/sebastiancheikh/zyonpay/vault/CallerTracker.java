package me.sebastiancheikh.zyonpay.vault;

import java.util.List;
import java.util.regex.Pattern;

/** Finds which plugin class called Vault, to store it as the reason of the transaction. */
final class CallerTracker {

    private static final StackWalker WALKER = StackWalker.getInstance();

    private CallerTracker() {
    }

    /**
     * @param verbosity 0 = off, 1 = class, 2 = class#method, 3 = class#method:line
     * @param ignored   classes matching any of these patterns are skipped (server, Vault and ZyonPay frames)
     * @return the first frame outside {@code ignored}, or null if disabled or none is found
     */
    static String capture(int verbosity, List<Pattern> ignored) {
        if (verbosity <= 0) {
            return null;
        }
        return WALKER.walk(frames -> frames
                .filter(frame -> !isInternal(frame.getClassName(), ignored))
                .findFirst()
                .map(frame -> switch (verbosity) {
                    case 1 -> frame.getClassName();
                    case 2 -> frame.getClassName() + "#" + frame.getMethodName();
                    default -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
                })
                .orElse(null));
    }

    private static boolean isInternal(String className, List<Pattern> ignored) {
        return className.startsWith("java.") || className.startsWith("jdk.")
                || className.startsWith("net.milkbowl.vault.")
                || ignored.stream().anyMatch(pattern -> pattern.matcher(className).find());
    }
}
