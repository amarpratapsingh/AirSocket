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
}
