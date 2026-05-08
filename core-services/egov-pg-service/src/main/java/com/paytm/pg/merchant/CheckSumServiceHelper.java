package com.paytm.pg.merchant;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal in-repo replacement for legacy paytm-checksum helper.
 * Keeps the same API used by PaytmGateway.
 */
public final class CheckSumServiceHelper {

    private static final String IV = "@@@@&&&&####$$$$";
    private static final String AES_CIPHER = "AES/CBC/PKCS5Padding";
    private static final String AES_ALGO = "AES";
    private static final String SHA_256 = "SHA-256";
    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final CheckSumServiceHelper INSTANCE = new CheckSumServiceHelper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CheckSumServiceHelper() {
    }

    public static CheckSumServiceHelper getCheckSumServiceHelper() {
        return INSTANCE;
    }

    // Intentionally preserves legacy method name used by existing code.
    public String genrateCheckSum(String key, TreeMap<String, String> params) throws Exception {
        String paramsString = joinParams(params);
        String salt = randomAlphaNumeric(4);
        String hash = sha256(paramsString + "|" + salt) + salt;
        return encrypt(hash, key);
    }

    private String joinParams(TreeMap<String, String> params) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String field = entry.getValue();
            if (field == null || "null".equalsIgnoreCase(field)) {
                field = "";
            }
            if ("CHECKSUMHASH".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            values.add(field.replace("|", ""));
        }
        return String.join("|", values);
    }

    private String randomAlphaNumeric(int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return builder.toString();
    }

    private String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance(SHA_256);
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String encrypt(String input, String key) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_CIPHER);
        SecretKeySpec keySpec = new SecretKeySpec(normalizeAesKey(key), AES_ALGO);
        IvParameterSpec ivSpec = new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private byte[] normalizeAesKey(String key) {
        byte[] src = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[16];
        int len = Math.min(src.length, out.length);
        System.arraycopy(src, 0, out, 0, len);
        return out;
    }
}
