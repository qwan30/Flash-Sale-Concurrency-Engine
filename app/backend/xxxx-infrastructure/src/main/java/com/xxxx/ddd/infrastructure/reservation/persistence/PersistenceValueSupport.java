package com.xxxx.ddd.infrastructure.reservation.persistence;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

final class PersistenceValueSupport {

    private PersistenceValueSupport() {
    }

    static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length != 16) {
                throw new IllegalArgumentException("UUID binary value must contain 16 bytes");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(String.valueOf(value));
    }

    static String hex(Object value) {
        if (value instanceof byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(Character.forDigit((current >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(current & 0x0f, 16));
            }
            return result.toString();
        }
        return String.valueOf(value);
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return Instant.parse(String.valueOf(value).replace(' ', 'T') + "Z");
    }

    static void requireHash(String value, String name) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(name + " must be a 64-character hexadecimal digest");
        }
    }
}
