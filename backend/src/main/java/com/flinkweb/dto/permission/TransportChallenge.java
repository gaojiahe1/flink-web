package com.flinkweb.dto.permission;

/** 向浏览器公开一次性加密挑战与公钥，不包含任何私钥或明文凭据。 */
public record TransportChallenge(
    String id,
    String algorithm,
    String publicKey,
    String purpose,
    String target,
    String expiresAt) {}
