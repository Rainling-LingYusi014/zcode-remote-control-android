package com.dsha.zremote;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 应用锁：PIN 以 PBKDF2WithHmacSHA256(5 万轮 + 随机盐) 存储，不明文落盘。
 * 边界：锁的是界面不是数据；忘记 PIN = 卸载重装（密钥随数据走）。
 */
public final class AppLock {

    private static final int ITERATIONS = 50_000;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("zremote", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return p(c).getBoolean("lock_enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        p(c).edit().putBoolean("lock_enabled", on).apply();
    }

    public static boolean biometricEnabled(Context c) {
        return p(c).getBoolean("lock_biometric", true);
    }

    public static void setBiometricEnabled(Context c, boolean on) {
        p(c).edit().putBoolean("lock_biometric", on).apply();
    }

    public static boolean hasPin(Context c) {
        return p(c).getString("lock_hash", null) != null;
    }

    public static void setPin(Context c, String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(pin, salt);
            p(c).edit()
                    .putString("lock_hash", Base64.getEncoder().encodeToString(hash))
                    .putString("lock_salt", Base64.getEncoder().encodeToString(salt))
                    .apply();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean verify(Context c, String pin) {
        try {
            byte[] salt = Base64.getDecoder().decode(p(c).getString("lock_salt", ""));
            byte[] expect = Base64.getDecoder().decode(p(c).getString("lock_hash", ""));
            byte[] got = pbkdf2(pin, salt);
            if (got.length != expect.length) return false;
            int diff = 0;
            for (int i = 0; i < got.length; i++) diff |= got[i] ^ expect[i];
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clear(Context c) {
        p(c).edit().remove("lock_hash").remove("lock_salt").putBoolean("lock_enabled", false).apply();
    }

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private AppLock() {}
}
