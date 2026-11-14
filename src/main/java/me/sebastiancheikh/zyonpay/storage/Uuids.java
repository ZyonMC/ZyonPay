package me.sebastiancheikh.zyonpay.storage;

import java.nio.ByteBuffer;
import java.util.UUID;

/** UUID {@code <->} BINARY(16) conversion. */
final class Uuids {

    private Uuids() {
    }

    static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /** Accepts null (NULL column). */
    static UUID fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
