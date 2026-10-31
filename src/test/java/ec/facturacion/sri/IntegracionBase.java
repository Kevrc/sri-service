package ec.facturacion.sri;

import ec.facturacion.sri.sri.SriClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Postgres real (Testcontainers) y SRI simulado; el contexto de Spring se comparte entre las clases de test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt-secret=secreto-de-pruebas-de-al-menos-32-caracteres",
        "app.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.superadmin-email=root@test.ec",
        "app.superadmin-password=SuperClave-123456",
        "app.worker-intervalo-ms=3600000",
        "app.webhooks.intervalo-ms=3600000",
        "app.webhooks.permitir-http=true",
        "app.webhooks.permitir-red-privada=true"})
abstract class IntegracionBase {

    @ServiceConnection
    // Sin @Container: Spring lo arranca una vez y lo comparte entre clases (con @Container se detendría al cambiar de clase).
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    SriClient sri;
    @Autowired
    JdbcClient db;
    @Autowired
    JsonMapper json;
    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    protected record Resp(int status, JsonNode body) {}

    Resp llamar(String metodo, String ruta, String token, Object body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + ruta))
                .method(metodo, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body().isBlank() ? null : json.readTree(r.body()));
    }

    JsonNode login(String email, String pass) throws Exception {
        Resp r = llamar("POST", "/auth/login", null, Map.of("email", email, "password", pass));
        assertEquals(200, r.status(), String.valueOf(r.body()));
        return r.body();
    }

    String access(String email, String pass) throws Exception {
        return login(email, pass).get("accessToken").asString();
    }

    Resp subirCertificado(String token, long emisorId) throws Exception {
        String limite = "----limite";
        var out = new ByteArrayOutputStream();
        out.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"clave\"\r\n\r\nprueba123\r\n").getBytes());
        out.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"archivo\"; filename=\"f.p12\"\r\n"
                + "Content-Type: application/x-pkcs12\r\n\r\n").getBytes());
        out.write(Files.readAllBytes(Path.of("src/test/resources/prueba.p12")));
        out.write(("\r\n--" + limite + "--\r\n").getBytes());
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/emisores/" + emisorId + "/certificado"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + limite)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        var r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), json.readTree(r.body()));
    }

    protected ObjectNode factura() throws Exception {
        return (ObjectNode) json.readTree(Path.of("ejemplos/factura.json").toFile());
    }


    private static final java.util.concurrent.atomic.AtomicInteger N = new java.util.concurrent.atomic.AtomicInteger();

    /** Tenant nuevo con ADMIN, USER, emisor (con certificado) y punto de emisión 002-003. */
    protected record Empresa(String admin, String user, String ruc, long emisorId) {}

    protected Empresa nuevaEmpresa() throws Exception {
        int n = N.incrementAndGet();
        String ruc = String.format("09%08d001", n);
        String root = access("root@test.ec", "SuperClave-123456");
        long t = llamar("POST", "/tenants", root, Map.of("nombre", "T" + n)).body().get("id").asLong();
        String clave = "Clave-segura-123";
        llamar("POST", "/auth/register", root, Map.of("email", "a" + n + "@s.ec", "password", clave, "rol", "ADMIN", "tenantId", t));
        llamar("POST", "/auth/register", root, Map.of("email", "u" + n + "@s.ec", "password", clave, "rol", "USER", "tenantId", t));
        String admin = access("a" + n + "@s.ec", clave);
        long e = llamar("POST", "/emisores", admin, Map.of("ruc", ruc, "razonSocial", "EMISOR " + n, "dirMatriz", "Quito",
                "obligadoContabilidad", true, "contribuyenteEspecial", "12345")).body().get("id").asLong();
        assertEquals(200, subirCertificado(admin, e).status());
        llamar("POST", "/emisores/" + e + "/puntos-emision", admin, Map.of("estab", "002", "ptoEmi", "003", "dirEstablecimiento", "Sucursal Norte"));
        return new Empresa(admin, access("u" + n + "@s.ec", clave), ruc, e);
    }

    protected byte[] descargar(String ruta, String token) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + ruta))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode(), new String(r.body()));
        return r.body();
    }
}
