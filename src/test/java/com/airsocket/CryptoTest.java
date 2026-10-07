package com.airsocket;

import com.airsocket.crypto.Crypto;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CryptoTest
{
    @Test
    public void testEncryptDecrypt() throws Exception
    {
        String plaintext = "Hello AirSocket!";
        String passphrase = "super-secret-password";

        byte[] cipher = Crypto.encrypt(plaintext.getBytes("UTF-8"), passphrase);
        assertNotNull(cipher);
        assertTrue(cipher.length > 28); // 16 salt + 12 IV + some payload

        byte[] decrypted = Crypto.decrypt(cipher, passphrase);
        String decryptedStr = new String(decrypted, "UTF-8");
        assertEquals(plaintext, decryptedStr);
    }

    @Test
    public void testDecryptWithWrongPassphrase() throws Exception
    {
        String plaintext = "Secret Message";
        String passphrase = "correct-password";
        String wrongPassphrase = "wrong-password";

        byte[] cipher = Crypto.encrypt(plaintext.getBytes("UTF-8"), passphrase);
        assertThrows(Exception.class, () ->
        {
            Crypto.decrypt(cipher, wrongPassphrase);
        });
    }

    @Test
    public void testDecryptTamperedCiphertext() throws Exception
    {
        String plaintext = "Clean Data";
        String passphrase = "password";

        byte[] cipher = Crypto.encrypt(plaintext.getBytes("UTF-8"), passphrase);
        cipher[cipher.length - 1] ^= 0x01;

        assertThrows(Exception.class, () ->
        {
            Crypto.decrypt(cipher, passphrase);
        });
    }

    @Test
    public void testCharArrayPassphraseEncryptionAndDecryption() throws Exception
    {
        String plaintext = "Memory-Safe Encryption Test";
        char[] passphrase = "secure-char-password".toCharArray();

        byte[] cipher = Crypto.encrypt(plaintext.getBytes("UTF-8"), passphrase);
        assertNotNull(cipher);

        byte[] decrypted = Crypto.decrypt(cipher, passphrase);
        String decryptedStr = new String(decrypted, "UTF-8");
        assertEquals(plaintext, decryptedStr);

        Crypto.wipe(passphrase);
        for (char c : passphrase)
        {
            assertEquals('\0', c, "Passphrase buffer should be zeroed after wipe");
        }
    }

    @Test
    public void testWipeZeroesCharArray()
    {
        char[] buffer = "super-secret".toCharArray();
        Crypto.wipe(buffer);
        for (char c : buffer)
        {
            assertEquals('\0', c, "All characters should be overwritten with null byte");
        }

        // Wipe with null should not throw
        assertDoesNotThrow(() -> Crypto.wipe(null));
    }

    @Test
    public void testDeriveKeyWithCharArray() throws Exception
    {
        char[] passphrase = "key-derivation-secret".toCharArray();
        byte[] salt = new byte[Crypto.SALT_LENGTH];
        new java.security.SecureRandom().nextBytes(salt);

        javax.crypto.spec.SecretKeySpec key1 = Crypto.deriveKey(passphrase, salt);
        assertNotNull(key1);
        assertEquals("AES", key1.getAlgorithm());

        Crypto.wipe(passphrase);
    }

    @Test
    public void testDeriveChunkIvUniquenessAndDeterminism()
    {
        byte[] baseIv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] baseIvCopy = baseIv.clone();

        byte[] iv0 = Crypto.deriveChunkIv(baseIv, 0);
        byte[] iv0Again = Crypto.deriveChunkIv(baseIv, 0);
        byte[] iv1 = Crypto.deriveChunkIv(baseIv, 1);
        byte[] iv2 = Crypto.deriveChunkIv(baseIv, 2);
        byte[] ivMax = Crypto.deriveChunkIv(baseIv, Long.MAX_VALUE);

        // Deterministic
        assertArrayEquals(iv0, iv0Again, "deriveChunkIv must be deterministic for identical index");

        // Unique
        assertFalse(java.util.Arrays.equals(iv0, iv1), "IVs for index 0 and 1 must differ");
        assertFalse(java.util.Arrays.equals(iv1, iv2), "IVs for index 1 and 2 must differ");
        assertFalse(java.util.Arrays.equals(iv0, ivMax), "IVs for index 0 and MAX must differ");

        // Does not mutate baseIv
        assertArrayEquals(baseIvCopy, baseIv, "baseIv must not be modified in place");

        // Length validation
        assertEquals(Crypto.IV_LENGTH, iv0.length);
        assertThrows(IllegalArgumentException.class, () -> Crypto.deriveChunkIv(null, 0));
        assertThrows(IllegalArgumentException.class, () -> Crypto.deriveChunkIv(new byte[11], 0));
        assertThrows(IllegalArgumentException.class, () -> Crypto.deriveChunkIv(new byte[16], 0));
    }
}
