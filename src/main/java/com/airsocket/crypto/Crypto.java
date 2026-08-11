package com.airsocket.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

public class Crypto
{
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATION_COUNT = 65536;
    private static final int TAG_LENGTH_BITS = 128;

    public static byte[] encrypt(byte[] plaintext, String passphrase) throws Exception
    {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);

        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        Cipher cipher = getEncryptCipher(passphrase, salt, iv);
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] result = new byte[SALT_LENGTH + IV_LENGTH + ciphertext.length];
        System.arraycopy(salt, 0, result, 0, SALT_LENGTH);
        System.arraycopy(iv, 0, result, SALT_LENGTH, IV_LENGTH);
        System.arraycopy(ciphertext, 0, result, SALT_LENGTH + IV_LENGTH, ciphertext.length);

        return result;
    }

    public static Cipher getEncryptCipher(String passphrase, byte[] salt, byte[] iv) throws Exception
    {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
        return cipher;
    }

    public static byte[] decrypt(byte[] combined, String passphrase) throws Exception
    {
        if (combined.length < SALT_LENGTH + IV_LENGTH)
        {
            throw new IllegalArgumentException("Invalid cipher text length");
        }

        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - SALT_LENGTH - IV_LENGTH];

        System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
        System.arraycopy(combined, SALT_LENGTH, iv, 0, IV_LENGTH);
        System.arraycopy(combined, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertext.length);

        Cipher cipher = getDecryptCipher(passphrase, salt, iv);
        return cipher.doFinal(ciphertext);
    }

    public static Cipher getDecryptCipher(String passphrase, byte[] salt, byte[] iv) throws Exception
    {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
        return cipher;
    }
}
