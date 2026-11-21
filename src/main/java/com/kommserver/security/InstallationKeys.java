package com.kommserver.security;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;

/**
 * The installation's own EC key pair (P-384, PEM files in the keys folder) and the certificate signing request built
 * from it. Plain static code with no Spring in it, because it is needed twice: before the application context exists
 * (activation against the hub, see {@code PreBootActivation}) and by {@link JwtUtil} once it does. Both end up
 * with the same files.
 */
@Slf4j
public class InstallationKeys {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /** Loads the key pair from the two files, or generates and saves a new one when either is missing. */
    public static KeyPair loadOrGenerate(Path keysDir, Path privateFile, Path publicFile) throws Exception {
        if (Files.exists(privateFile) && Files.exists(publicFile)) {
            log.info("Loading existing EC key pair from {}", keysDir);
            return load(privateFile, publicFile);
        }

        log.info("Generating new EC P-384 key pair and saving to {}", keysDir);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", "BC");
        gen.initialize(new ECNamedCurveGenParameterSpec("P-384"));
        KeyPair keyPair = gen.generateKeyPair();
        save(keyPair, keysDir, privateFile, publicFile);
        return keyPair;
    }

    /** A PEM certificate signing request for {@code CN=<cn>}, signed with the key pair's private key. */
    public static String csrPem(KeyPair keyPair, String cn) {
        try {
            ContentSigner signer = new JcaContentSignerBuilder("SHA384withECDSA")
                    .setProvider("BC").build(keyPair.getPrivate());
            PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                    new X500Name("CN=" + cn), keyPair.getPublic())
                    .build(signer);
            StringWriter sw = new StringWriter();
            try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
                w.writeObject(csr);
            }
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate CSR", e);
        }
    }

    private static void save(KeyPair keyPair, Path keysDir, Path privateFile, Path publicFile) throws IOException {
        Files.createDirectories(keysDir);
        try (JcaPEMWriter w = new JcaPEMWriter(Files.newBufferedWriter(privateFile))) {
            w.writeObject(keyPair.getPrivate());
        }
        try (JcaPEMWriter w = new JcaPEMWriter(Files.newBufferedWriter(publicFile))) {
            w.writeObject(keyPair.getPublic());
        }
        log.info("EC P-384 key pair saved to {}", keysDir);
    }

    private static KeyPair load(Path privateFile, Path publicFile) throws Exception {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");

        PrivateKey privateKey;
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(privateFile))) {
            Object obj = parser.readObject();
            if (obj instanceof PEMKeyPair) {
                privateKey = converter.getPrivateKey(((PEMKeyPair) obj).getPrivateKeyInfo());
            } else if (obj instanceof PrivateKeyInfo) {
                privateKey = converter.getPrivateKey((PrivateKeyInfo) obj);
            } else {
                throw new IllegalArgumentException("Unsupported private key format: " + obj.getClass().getName());
            }
        }

        PublicKey publicKey;
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(publicFile))) {
            Object obj = parser.readObject();
            if (obj instanceof SubjectPublicKeyInfo) {
                publicKey = converter.getPublicKey((SubjectPublicKeyInfo) obj);
            } else if (obj instanceof PEMKeyPair) {
                publicKey = converter.getPublicKey(((PEMKeyPair) obj).getPublicKeyInfo());
            } else {
                throw new IllegalArgumentException("Unsupported public key format: " + obj.getClass().getName());
            }
        }

        return new KeyPair(publicKey, privateKey);
    }
}
