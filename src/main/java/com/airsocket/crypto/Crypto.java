package com.airsocket.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public class Crypto
{
    public static final int SALT_LENGTH = 16;
    public static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATION_COUNT = 65536;
    private static final int TAG_LENGTH_BITS = 128;

    public static byte[] deriveChunkIv(byte[] baseIv, long chunkIndex)
    {
        if (baseIv == null || baseIv.length != IV_LENGTH)
        {
            throw new IllegalArgumentException("Base IV must be exactly " + IV_LENGTH + " bytes");
        }
        byte[] chunkIv = baseIv.clone();
        ByteBuffer buffer = ByteBuffer.wrap(chunkIv);
        long originalCounter = buffer.getLong(4);
        buffer.putLong(4, originalCounter ^ chunkIndex);
        return chunkIv;
    }

    public static void wipe(char[] array)
    {
        if (array != null)
        {
            Arrays.fill(array, '\0');
        }
    }

    public static SecretKeySpec deriveKey(char[] passphrase, byte[] salt) throws Exception
    {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATION_COUNT, KEY_LENGTH);
        try
        {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), "AES");
        }
        finally
        {
            spec.clearPassword();
        }
    }

    public static SecretKeySpec deriveKey(String passphrase, byte[] salt) throws Exception
    {
        char[] chars = passphrase != null ? passphrase.toCharArray() : new char[0];
        try
        {
            return deriveKey(chars, salt);
        }
        finally
        {
            wipe(chars);
        }
    }

    public static Cipher getCipher(SecretKeySpec secretKey, byte[] iv, int mode) throws Exception
    {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
        cipher.init(mode, secretKey, gcmSpec);
        return cipher;
    }

    public static byte[] encrypt(byte[] plaintext, char[] passphrase) throws Exception
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

    public static byte[] encrypt(byte[] plaintext, String passphrase) throws Exception
    {
        char[] chars = passphrase != null ? passphrase.toCharArray() : new char[0];
        try
        {
            return encrypt(plaintext, chars);
        }
        finally
        {
            wipe(chars);
        }
    }

    public static Cipher getEncryptCipher(char[] passphrase, byte[] salt, byte[] iv) throws Exception
    {
        SecretKeySpec secretKey = deriveKey(passphrase, salt);
        return getCipher(secretKey, iv, Cipher.ENCRYPT_MODE);
    }

    public static Cipher getEncryptCipher(String passphrase, byte[] salt, byte[] iv) throws Exception
    {
        char[] chars = passphrase != null ? passphrase.toCharArray() : new char[0];
        try
        {
            return getEncryptCipher(chars, salt, iv);
        }
        finally
        {
            wipe(chars);
        }
    }

    public static byte[] decrypt(byte[] combined, char[] passphrase) throws Exception
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

    public static byte[] decrypt(byte[] combined, String passphrase) throws Exception
    {
        char[] chars = passphrase != null ? passphrase.toCharArray() : new char[0];
        try
        {
            return decrypt(combined, chars);
        }
        finally
        {
            wipe(chars);
        }
    }

    public static Cipher getDecryptCipher(char[] passphrase, byte[] salt, byte[] iv) throws Exception
    {
        SecretKeySpec secretKey = deriveKey(passphrase, salt);
        return getCipher(secretKey, iv, Cipher.DECRYPT_MODE);
    }

    public static Cipher getDecryptCipher(String passphrase, byte[] salt, byte[] iv) throws Exception
    {
        char[] chars = passphrase != null ? passphrase.toCharArray() : new char[0];
        try
        {
            return getDecryptCipher(chars, salt, iv);
        }
        finally
        {
            wipe(chars);
        }
    }
}
