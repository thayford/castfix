package com.thayford.castfix;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * adb connection to this TV with the app's own key. The key is created on first use, paired once through
 * Wireless debugging (PairReceiver) and kept in the app's private files.
 */
final class AdbManager extends AbsAdbConnectionManager {
    private static final long DAY_MS = 86_400_000L;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(20, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
        File keyFile = new File(context.getFilesDir(), "adb.key");
        File certFile = new File(context.getFilesDir(), "adb.crt");
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            privateKey = pair.getPrivate();
            certificate = selfSigned(pair);
            Files.write(keyFile.toPath(), privateKey.getEncoded());
            Files.write(certFile.toPath(), certificate.getEncoded());
        }
    }

    /** adbd only checks the public key, but give the certificate a lifetime longer than the TV's. */
    private static Certificate selfSigned(KeyPair pair) throws Exception {
        BouncyCastleProvider bc = new BouncyCastleProvider();
        X500Name name = new X500Name("CN=CastFix");
        long now = System.currentTimeMillis();
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(name, BigInteger.valueOf(now),
                new Date(now - DAY_MS), new Date(now + 30 * 365 * DAY_MS), name,
                SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.getPrivate());
        return new JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer));
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "CastFix";
    }
}
