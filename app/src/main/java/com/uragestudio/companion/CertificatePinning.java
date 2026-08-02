package com.uragestudio.companion;

import android.util.Base64;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class CertificatePinning {
    private CertificatePinning() {}

    public static javax.net.ssl.SSLSocketFactory socketFactory(String configuredPin) throws Exception {
        String expected = normalizePin(configuredPin);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509TrustManager platform = null;
        for (javax.net.ssl.TrustManager candidate : factory.getTrustManagers()) {
            if (candidate instanceof X509TrustManager) platform = (X509TrustManager) candidate;
        }
        if (platform == null) throw new IllegalStateException("Platform certificate trust manager is unavailable.");
        X509TrustManager delegate = platform;
        X509TrustManager pinned = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                delegate.checkClientTrusted(chain, authType);
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                delegate.checkServerTrusted(chain, authType);
                if (expected.isEmpty()) return;
                if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Server sent no certificate.");
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    String actualBase64 = Base64.encodeToString(digest, Base64.NO_WRAP);
                    StringBuilder actualHex = new StringBuilder();
                    for (byte value : digest) actualHex.append(String.format("%02x", value));
                    if (!expected.equals(actualBase64) && !expected.equalsIgnoreCase(actualHex.toString())) {
                        throw new java.security.cert.CertificateException("Dashboard certificate pin mismatch.");
                    }
                } catch (java.security.cert.CertificateException error) {
                    throw error;
                } catch (Exception error) {
                    throw new java.security.cert.CertificateException("Could not verify the dashboard certificate pin.", error);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new javax.net.ssl.TrustManager[]{pinned}, null);
        return context.getSocketFactory();
    }

    private static String normalizePin(String value) {
        return String.valueOf(value == null ? "" : value)
            .trim().replace("sha256/", "").replace("SHA256/", "").replace(":", "").replaceAll("\\s+", "");
    }
}
