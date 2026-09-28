package com.flinkweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.dto.permission.TransportChallenge;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;

/** 模拟外部客户端生成标准加密信封，测试真实密码边界而不绕过解密。 */
public final class CredentialEnvelopeFixture {
  /** 根据公开挑战生成随机密钥信封，用于 MVC 与重放/篡改回归。 */
  public static String encrypt(TransportChallenge challenge, String value) throws Exception {
    var rng = new SecureRandom();
    byte[] key = new byte[32], iv = new byte[12];
    rng.nextBytes(key);
    rng.nextBytes(iv);
    var aes = Cipher.getInstance("AES/GCM/NoPadding");
    aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    aes.updateAAD(
        (challenge.id() + "\n" + challenge.purpose() + "\n" + challenge.target())
            .getBytes(StandardCharsets.UTF_8));
    var encrypted = aes.doFinal(value.getBytes(StandardCharsets.UTF_8));
    var publicKey =
        KeyFactory.getInstance("RSA")
            .generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(challenge.publicKey())));
    var rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
    rsa.init(
        Cipher.ENCRYPT_MODE,
        publicKey,
        new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
    var encoder = Base64.getEncoder();
    return "fwenc1:"
        + new ObjectMapper()
            .createObjectNode()
            .put("id", challenge.id())
            .put("key", encoder.encodeToString(rsa.doFinal(key)))
            .put("iv", encoder.encodeToString(iv))
            .put("data", encoder.encodeToString(encrypted));
  }
}
