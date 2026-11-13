package ge.cynthia.openservice;

import org.bouncycastle.crypto.generators.SCrypt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
    private Passwords() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String token() {
        byte[] value = new byte[24];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String hash(String password) throws GeneralSecurityException {
        String salt = token();
        return "pbkdf2:sha256:600000$"
                + salt
                + "$"
                + HexFormat.of().formatHex(derive(password, salt, 600000));
    }

    private static byte[] derive(String password, String salt, int iterations)
            throws GeneralSecurityException {
        var spec =
                new PBEKeySpec(
                        password.toCharArray(),
                        salt.getBytes(StandardCharsets.UTF_8),
                        iterations,
                        256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean matches(String encoded, String password) throws GeneralSecurityException {
        try {
            String[] parts = encoded.split("\\$", -1), method = parts[0].split(":");
            if (parts.length != 3) return false;
            byte[] actual;
            if (method.length == 3 && method[0].equals("pbkdf2") && method[1].equals("sha256")) {
                int iterations = Integer.parseInt(method[2]);
                if (iterations < 1 || iterations > 10000000) return false;
                actual = derive(password, parts[1], iterations);
            } else if (method.length == 4 && method[0].equals("scrypt")) {
                int n = Integer.parseInt(method[1]),
                        r = Integer.parseInt(method[2]),
                        p = Integer.parseInt(method[3]);
                if (n < 2 || n > 65536 || r < 1 || r > 16 || p < 1 || p > 4) return false;
                actual =
                        SCrypt.generate(
                                password.getBytes(StandardCharsets.UTF_8),
                                parts[1].getBytes(StandardCharsets.UTF_8),
                                n,
                                r,
                                p,
                                64);
            } else return false;
            return MessageDigest.isEqual(actual, HexFormat.of().parseHex(parts[2]));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
