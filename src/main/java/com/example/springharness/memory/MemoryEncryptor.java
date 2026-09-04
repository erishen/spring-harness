package com.example.springharness.memory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Memory 内容加密工具类（AES/GCM/NoPadding）。
 *
 * <p>隐私保护：对用户记忆内容进行加密存储，防止数据库文件泄露导致隐私暴露。
 * 加密格式：Base64(IV[12] + 密文 + Tag[16])，可通过前缀 "enc:" 识别。
 *
 * <p>注意：开启加密后，SQL LIKE 全文检索无法在密文上工作，
 * MemoryStore.search() 会自动降级为加载全部记录后在内存中匹配。
 *
 * <p>密钥来源（优先级）：
 * <ol>
 *   <li>环境变量 MEMORY_ENCRYPT_KEY（推荐，用户自定义）</li>
 *   <li>未配置时使用基于机器特征的默认密钥（user.name + user.home + os.name 的 SHA-256）</li>
 * </ol>
 */
public final class MemoryEncryptor {

    /** 加密内容前缀，用于识别是否已加密 */
    public static final String ENC_PREFIX = "enc:";

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int KEY_LENGTH = 32; // AES-256

    private final SecretKeySpec secretKey;

    /**
     * 使用指定密钥创建加密器。
     *
     * @param key 密钥字符串（任意长度，会被 SHA-256 哈希为 32 字节）
     */
    public MemoryEncryptor(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MemoryEncryptor 失败", e);
        }
    }

    /**
     * 加密文本。
     *
     * @param plaintext 明文
     * @return 加密后的 Base64 字符串（带 "enc:" 前缀）
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH * 8, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV + 密文（GCM 的 Tag 已包含在 ciphertext 末尾）
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Memory 加密失败", e);
        }
    }

    /**
     * 解密文本。
     *
     * @param encrypted 加密后的字符串（带 "enc:" 前缀）
     * @return 明文；如果输入不是加密格式，直接返回原文
     */
    public String decrypt(String encrypted) {
        if (encrypted == null) return null;
        if (!encrypted.startsWith(ENC_PREFIX)) {
            // 未加密的旧数据，直接返回
            return encrypted;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted.substring(ENC_PREFIX.length()));

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH * 8, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败（密钥不匹配或数据损坏），返回原文并记录警告
            System.err.println("[MemoryEncryptor] 解密失败，返回原文: " + e.getMessage());
            return encrypted;
        }
    }

    /**
     * 判断文本是否为加密格式。
     */
    public static boolean isEncrypted(String text) {
        return text != null && text.startsWith(ENC_PREFIX);
    }
}
