package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestTemplateConfigTest {

    @Test
    void restTemplate_whenTlsProbeDisabled_returnsDefaultRestTemplate() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "tlsProbeEnabled", false);

        RestTemplate restTemplate = config.restTemplate();

        assertThat(restTemplate).isNotNull();
    }

    @Test
    void restTemplateSupplier_whenTlsProbeDisabled_returnsNewInstanceEachCall() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "tlsProbeEnabled", false);

        Supplier<RestTemplate> supplier = config.restTemplateSupplier();
        RestTemplate first = supplier.get();
        RestTemplate second = supplier.get();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void restTemplateSupplier_whenTlsProbeEnabledAndInvalidPort_wrapsFailure() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "tlsProbeEnabled", true);
        ReflectionTestUtils.setField(config, "targetHost", "localhost");
        ReflectionTestUtils.setField(config, "targetPort", -1);

        Supplier<RestTemplate> supplier = config.restTemplateSupplier();

        assertThatThrownBy(supplier::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to build RestTemplate")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doSslHandshake_returnsPeerCertificates() throws IOException {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "targetHost", "example.org");
        ReflectionTestUtils.setField(config, "targetPort", 443);

        SSLSocketFactory factory = mock(SSLSocketFactory.class);
        SSLSocket socket = mock(SSLSocket.class);
        SSLSession session = mock(SSLSession.class);
        X509Certificate certificate = mock(X509Certificate.class);

        when(factory.createSocket("example.org", 443)).thenReturn(socket);
        when(socket.getSession()).thenReturn(session);
        when(session.getPeerCertificates()).thenReturn(new X509Certificate[]{certificate});

        X509Certificate[] certificates = ReflectionTestUtils.invokeMethod(config, "doSslHandshake", factory);

        assertThat(certificates).containsExactly(certificate);
        verify(socket).startHandshake();
        verify(socket).close();
    }

    @Test
    void doSslHandshake_propagatesSocketFailure() throws IOException {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "targetHost", "example.org");
        ReflectionTestUtils.setField(config, "targetPort", 443);

        SSLSocketFactory factory = mock(SSLSocketFactory.class);
        when(factory.createSocket("example.org", 443)).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(config, "doSslHandshake", factory))
                .rootCause()
                .isInstanceOf(IOException.class)
                .hasMessage("boom");
    }

    @Test
    void fetchServerCertificateChain_whenEndpointIsUnreachable_raisesIoFailure() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "targetHost", "127.0.0.1");
        ReflectionTestUtils.setField(config, "targetPort", 1);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(config, "fetchServerCertificateChain"))
                .rootCause()
                .isInstanceOf(ConnectException.class);
    }
}
