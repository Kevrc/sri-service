package ec.facturacion.sri.webhooks;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebhooksTest {

    @Test
    void hmacSha256SegunRfc4231() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Webhooks.hmac("Jefe".getBytes(StandardCharsets.UTF_8), "what do ya want for nothing?"));
    }

    @Test
    void bloqueaRedesPrivadas() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1", "::ffff:127.0.0.1"})
            assertTrue(Webhooks.esPrivada(InetAddress.getByName(ip)), ip);
        for (String ip : new String[]{"8.8.8.8", "186.101.1.1", "2001:4860:4860::8888"})
            assertFalse(Webhooks.esPrivada(InetAddress.getByName(ip)), ip);
    }

    @Test
    void validaUrl() {
        var w = new Webhooks(null, null, false, false);
        for (String url : new String[]{"http://8.8.8.8/x", "https://127.0.0.1/x", "https://169.254.169.254/latest",
                "https://usuario:clave@8.8.8.8/x", "ftp://8.8.8.8", "https://[::1]/x", "nada"})
            assertThrows(IllegalArgumentException.class, () -> w.validarUrl(url), url);
        assertDoesNotThrow(() -> w.validarUrl("https://8.8.8.8/webhook"));
    }
}
