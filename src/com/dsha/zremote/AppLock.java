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
    /** 连续输错 5 次开始退避：30s 起，逐次翻倍，上限 10 分钟（审查 P2：PIN 无防爆破）。 */
    private static final int FREE_TRIES = 5;
    private static final long BASE_DELAY_MS = 30_000L;
    private static final long MAX_DELAY_MS = 10 * 60_000L;

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

    /** 剩余退避时间（毫秒，0=现在可以尝试）。 */
    public static long lockoutRemainingMs(Context c) {
        SharedPreferences p = p(c);
        long wall = p.getLong("lock_until", 0L) - System.currentTimeMillis();
        long elapsed = p.getLong("lock_until_elapsed", 0L) - android.os.SystemClock.elapsedRealtime();
        // 双水位取较大值：改系统时间往前拨只能骗过 wall，骗不过 elapsed（审查 R4e）。
        // 两个值都只在 0~上限内可信：单个值超过退避上限说明对应时钟被动过/重启归零，弃用该水位
        if (wall > MAX_DELAY_MS || wall < 0) wall = 0;
        if (elapsed > MAX_DELAY_MS || elapsed < 0) elapsed = 0;
        return Math.max(wall, elapsed);
    }

    public static boolean verify(Context c, String pin) {
        if (lockoutRemainingMs(c) > 0) return false;
        boolean ok = matches(c, pin);
        if (ok) {
            p(c).edit().remove("lock_fails").remove("lock_until")
                    .remove("lock_until_elapsed").apply();
        } else {
            int fails = p(c).getInt("lock_fails", 0) + 1;
            android.content.SharedPreferences.Editor e = p(c).edit().putInt("lock_fails", fails);
            if (fails >= FREE_TRIES) {
                long delay = Math.min(BASE_DELAY_MS << Math.min(fails - FREE_TRIES, 5), MAX_DELAY_MS);
                e.putLong("lock_until", System.currentTimeMillis() + delay)
                        .putLong("lock_until_elapsed",
                                android.os.SystemClock.elapsedRealtime() + delay);
            }
            e.apply();
        }
        return ok;
    }

    private static boolean matches(Context c, String pin) {
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
        p(c).edit().remove("lock_hash").remove("lock_salt")
                .remove("lock_fails").remove("lock_until").remove("lock_until_elapsed")
                .putBoolean("lock_enabled", false).apply();
    }

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private AppLock() {}
}
