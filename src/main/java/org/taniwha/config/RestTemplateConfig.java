package org.taniwha.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;

@Configuration
public class RestTemplateConfig {

    private static final Logger logger = LoggerFactory.getLogger(RestTemplateConfig.class);

    @Value("${tls.probe.enabled:false}")
    private boolean tlsProbeEnabled;

    @Value("${tls.probe.host:semantics.inf.um.es}")
    private String targetHost;

    @Value("${tls.probe.port:443}")
    private int targetPort;


    @Bean
    public RestTemplate restTemplate() {
        return restTemplateSupplier().get();
    }

    @Bean
    public Supplier<RestTemplate> restTemplateSupplier() {
        return () -> {
            try {
                return buildRestTemplate();
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Failed to build RestTemplate", e);
                throw new IllegalStateException("Failed to build RestTemplate", e);
            }
        };
    }

    private RestTemplate buildRestTemplate() throws Exception {
        if (!tlsProbeEnabled) {
            logger.info("TLS probe disabled; using default RestTemplate");
            return new RestTemplate();
        }
        logger.info("Initializing default TrustManagerFactory...");
        TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaultTmf.init((KeyStore) null);

        logger.info("Fetching certificate chain from {}", targetHost);
        X509Certificate[] serverCerts = fetchServerCertificateChain();
        logger.info("Fetched {} certificates from server", serverCerts.length);

        KeyStore combinedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        combinedKeyStore.load(null, null);

        logger.info("Adding default trusted certificates to combined key store...");
        for (TrustManager tm : defaultTmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509Tm) {
                for (X509Certificate cert : x509Tm.getAcceptedIssuers()) {
                    String alias = cert.getSubjectX500Principal().getName() + "_" + cert.getSerialNumber();
                    combinedKeyStore.setCertificateEntry(alias, cert);
                }
            }
        }

        logger.info("Adding server certificates to combined key store...");
        for (int i = 0; i < serverCerts.length; i++) {
            X509Certificate cert = serverCerts[i];
            String alias = cert.getSubjectX500Principal().getName() + "_server_" + i;
            combinedKeyStore.setCertificateEntry(alias, cert);
        }

        logger.info("Initializing combined TrustManagerFactory...");
        TrustManagerFactory combinedTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        combinedTmf.init(combinedKeyStore);

        logger.info("Setting up SSL context with combined trust managers...");
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, combinedTmf.getTrustManagers(), null);

        logger.info("Building HTTP client with custom SSL context...");
        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                sslContext,
                new DefaultHostnameVerifier()
        );
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(socketFactory)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        return new RestTemplate(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(httpClient));
    }

    private X509Certificate[] fetchServerCertificateChain() throws IOException {
        try {
            logger.info("Creating SSL context to fetch server certificate chain...");
            SSLContext trustAllContext = SSLContext.getInstance("TLS");
            trustAllContext.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            // Trust all clients: only used to fetch the server's certificate chain for bootstrap.
                        }
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            // Trust all servers: only used to fetch the server's certificate chain for bootstrap.
                        }
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            }, new java.security.SecureRandom());

            SSLSocketFactory factory = trustAllContext.getSocketFactory();
            return doSslHandshake(factory);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.error("Error creating trust-all SSL context", e);
            throw new IllegalStateException("Error creating trust-all SSL context", e);
        }
    }

    private X509Certificate[] doSslHandshake(SSLSocketFactory factory) throws IOException {
        try (SSLSocket socket = (SSLSocket) factory.createSocket(targetHost, targetPort)) {
            socket.startHandshake();
            SSLSession session = socket.getSession();
            return (X509Certificate[]) session.getPeerCertificates();
        }
    }
}