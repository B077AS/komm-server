package com.kommserver.security;

import com.kommserver.config.DataDir;
import com.kommserver.model.db.Installation;
import com.kommserver.repository.InstallationRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final InstallationRepository installationRepository;

    @Value("${jwt.issuer}")
    private String issuer;

    @Value("${jwt.access-token.expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token.expiration}")
    private long refreshTokenExpiration;

    @Value("${jwt.keys.directory}")
    private String keysDirectory;

    @Value("${jwt.keys.private}")
    private String privateKeyFile;

    @Value("${jwt.keys.public}")
    private String publicKeyFile;

    // ── Hub keys (loaded from DB / set manually) ──────────────────────────────
    private PublicKey hubPublicKey;

    // ── Own keypair — persisted across restarts ───────────────────────────────
    private PrivateKey localPrivateKey;
    private PublicKey localPublicKey;

    @PostConstruct
    public void init() {
        Security.addProvider(new BouncyCastleProvider());

        try {
            KeyPair local = loadOrGenerateKeyPair();
            this.localPrivateKey = local.getPrivate();
            this.localPublicKey = local.getPublic();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or generate installation key pair", e);
        }

        // Try to load hub-issued keys from DB
        List<Installation> rows = installationRepository.findAll();
        if (rows.size() == 1) {
            Installation inst = rows.get(0);
            try {
                this.hubPublicKey = pemToPublicKey(inst.getHubPublicKey());
                log.info("Hub key loaded from DB for installation {}", inst.getInstallationId());
            } catch (Exception e) {
                log.warn("DB row found but keys could not be loaded — call loadInstallationKeys() manually", e);
            }
        } else {
            log.info("No installation row found — hub keys not loaded yet");
        }
    }

    // The key pair is shared with the activation that runs before Spring starts (PreBootActivation), which
    // generates it on a first start so that the CSR it sends to the hub is signed by the very same key.
    private KeyPair loadOrGenerateKeyPair() throws Exception {
        return InstallationKeys.loadOrGenerate(
                DataDir.resolve(keysDirectory), DataDir.resolve(privateKeyFile), DataDir.resolve(publicKeyFile));
    }

    /**
     * Short-lived token (30 s) signed with the installation's own private key.
     * The hub verifies it using the public key embedded in the stored certificate,
     * proving the connecting party holds the corresponding private key.
     */
    public String generateConnectToken(UUID installationId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(installationId.toString())
                .claim("type", "connect")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.SECONDS)))
                .signWith(localPrivateKey, Jwts.SIG.ES384)
                .compact();
    }

    public String generateAccessToken(UUID userId, String email, UUID serverId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .claim("userId", userId.toString())
                .claim("serverId", serverId.toString())
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration, ChronoUnit.SECONDS)))
                .signWith(localPrivateKey, Jwts.SIG.ES384)
                .compact();
    }

    public String generateRefreshToken(UUID userId, String email, UUID serverId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .claim("userId", userId.toString())
                .claim("serverId", serverId.toString())
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenExpiration, ChronoUnit.SECONDS)))
                .signWith(localPrivateKey, Jwts.SIG.ES384)
                .compact();
    }

    // ── Token validation ──────────────────────────────────────────────────────

    public Claims validateLocalToken(String token) {
        return Jwts.parser()
                .verifyWith(localPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Claims validateHubToken(String token) {
        requireHubPublicKey();
        return Jwts.parser()
                .verifyWith(hubPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(String token) {
        Claims claims = validateLocalToken(token);
        return UUID.fromString(claims.get("userId", String.class));
    }

    public UUID extractServerId(String token) {
        Claims claims = validateLocalToken(token);
        return UUID.fromString(claims.get("serverId", String.class));
    }

    private PublicKey pemToPublicKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof SubjectPublicKeyInfo)
                return converter.getPublicKey((SubjectPublicKeyInfo) obj);
            if (obj instanceof PEMKeyPair)
                return converter.getPublicKey(((PEMKeyPair) obj).getPublicKeyInfo());
            throw new IllegalArgumentException("Unsupported public key format: " + obj.getClass().getName());
        }
    }

    public void setHubPublicKeyFromPem(String pem) {
        try {
            this.hubPublicKey = pemToPublicKey(pem);
            log.info("Hub public key set from PEM");
        } catch (Exception e) {
            throw new RuntimeException("Failed to set hub public key from PEM", e);
        }
    }

    private void requireHubPublicKey() {
        if (hubPublicKey == null)
            throw new IllegalStateException("Hub public key not loaded — register with hub first");
    }

    public enum TokenType {
        ACCESS, REFRESH
    }
}
