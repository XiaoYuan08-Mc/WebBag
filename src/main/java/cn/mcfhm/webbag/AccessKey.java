package cn.mcfhm.webbag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 单一口令式访问控制。口令保存在 config.yml 的 access-key。
 */
public final class AccessKey {

    private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private volatile String value;

    public AccessKey(String value) {
        this.value = value == null ? "" : value.trim();
    }

    public String value() {
        return value;
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    public synchronized String rotate() {
        value = generate();
        return value;
    }

    public boolean matches(String candidate) {
        if (value.isEmpty() || candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8));
    }

    public String generate() {
        char[] out = new char[24];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
