package ec.facturacion.sri;

import ec.facturacion.sri.sri.SriClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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

/** Flujo completo contra un Postgres real, con el SRI simulado. */
/** Flujo completo contra un Postgres real, con el SRI simulado. */
class FlujoIntegracionTest extends IntegracionBase {

    @Test
    void flujoCompleto() throws Exception {
        String root = access("root@test.ec", "SuperClave-123456");

        long tA = llamar("POST", "/tenants", root, Map.of("nombre", "Empresa A")).body().get("id").asLong();
        long tB = llamar("POST", "/tenants", root, Map.of("nombre", "Empresa B")).body().get("id").asLong();
        String clave = "Clave-segura-123";
        assertEquals(201, llamar("POST", "/auth/register", root, Map.of("email", "admin@a.ec", "password", clave, "rol", "ADMIN", "tenantId", tA)).status());
        assertEquals(201, llamar("POST", "/auth/register", root, Map.of("email", "admin@b.ec", "password", clave, "rol", "ADMIN", "tenantId", tB)).status());
        String adminA = access("admin@a.ec", clave);
        String adminB = access("admin@b.ec", clave);

        // Un ADMIN no puede crear usuarios en otro tenant: se fuerza al suyo.
        assertEquals(201, llamar("POST", "/auth/register", adminA, Map.of("email", "user@a.ec", "password", clave, "rol", "USER", "tenantId", tB)).status());
        assertEquals(tA, db.sql("select tenant_id from usuario where email = 'user@a.ec'").query(Long.class).single());
        assertEquals(403, llamar("POST", "/auth/register", adminA, Map.of("email", "x@a.ec", "password", clave, "rol", "SUPERADMIN")).status());
        String userA = access("user@a.ec", clave);

        // Emisor, certificado y punto de emisión
        Resp em = llamar("POST", "/emisores", adminA, Map.of("ruc", "0999999999001", "razonSocial", "MI EMPRESA S.A.",
                "dirMatriz", "Guayaquil", "obligadoContabilidad", true));
        assertEquals(201, em.status(), String.valueOf(em.body()));
        long emisorId = em.body().get("id").asLong();
        assertEquals(403, llamar("POST", "/emisores", userA, Map.of("ruc", "0999999998001", "razonSocial", "X", "dirMatriz", "Y")).status());
        Resp cert = subirCertificado(adminA, emisorId);
        assertEquals(200, cert.status(), String.valueOf(cert.body()));
        assertTrue(cert.body().get("vigente").asBoolean());
        byte[] guardado = db.sql("select cert_p12 from emisor where id = ?").param(emisorId).query(byte[].class).single();
        assertFalse(new String(guardado, StandardCharsets.ISO_8859_1).contains("prueba"), "el .p12 debe estar cifrado");
        assertEquals(201, llamar("POST", "/emisores/" + emisorId + "/puntos-emision", adminA,
                Map.of("estab", "001", "ptoEmi", "001", "dirEstablecimiento", "Guayaquil")).status());

        // Emisión autorizada
        when(sri.enviar(any())).thenReturn(new SriClient.Respuesta("RECIBIDA", List.of()));
        when(sri.consultar(anyString())).thenAnswer(inv -> new SriClient.Autorizacion("AUTORIZADO", inv.getArgument(0),
                "2026-10-31T10:00:00-05:00", List.of(), "<autorizacion/>"));
        Resp f1 = llamar("POST", "/sri/comprobantes", userA, factura(), "Idempotency-Key", "pedido-1");
        assertEquals(201, f1.status(), String.valueOf(f1.body()));
        assertEquals("AUTORIZADO", f1.body().get("estado").asString());
        assertEquals("000000001", f1.body().get("secuencial").asString());
        String claveAcceso = f1.body().get("claveAcceso").asString();
        assertTrue(claveAcceso.startsWith("311020260109999999990011001001000000001"));
        assertEquals("<autorizacion/>", f1.body().get("xmlAutorizado").asString());

        // Idempotencia: el mismo pedido no se emite dos veces
        Resp repetida = llamar("POST", "/sri/comprobantes", userA, factura(), "Idempotency-Key", "pedido-1");
        assertEquals(200, repetida.status());
        assertEquals(claveAcceso, repetida.body().get("claveAcceso").asString());
        assertEquals("000000002", llamar("POST", "/sri/comprobantes", userA, factura()).body().get("secuencial").asString());

        // Aislamiento entre tenants
        assertEquals(404, llamar("GET", "/sri/comprobantes/" + claveAcceso, adminB, null).status());
        assertEquals(404, llamar("GET", "/emisores/" + emisorId, adminB, null).status());
        assertEquals(0, llamar("GET", "/sri/comprobantes", adminB, null).body().get("total").asLong());
        assertEquals(400, llamar("POST", "/sri/comprobantes", adminB, factura()).status(), "B no puede usar el emisor de A");
        assertEquals(403, llamar("GET", "/sri/comprobantes", root, null).status(), "SUPERADMIN no factura");

        // SRI caído: queda PENDIENTE guardado y se recupera después
        doThrow(new IOException("timeout")).when(sri).enviar(any());
        Resp caida = llamar("POST", "/sri/comprobantes", userA, factura());
        assertEquals("PENDIENTE", caida.body().get("estado").asString());
        assertEquals(1, caida.body().get("intentos").asInt());
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        String claveCaida = caida.body().get("claveAcceso").asString();
        assertEquals("AUTORIZADO", llamar("GET", "/sri/verificar/" + claveCaida, userA, null).body().get("estado").asString());

        // Rechazo del SRI
        doReturn(new SriClient.Respuesta("DEVUELTA",
                List.of(Map.of("identificador", "35", "mensaje", "ARCHIVO NO CUMPLE ESTRUCTURA XML")))).when(sri).enviar(any());
        Resp devuelta = llamar("POST", "/sri/comprobantes", userA, factura());
        assertEquals("DEVUELTA", devuelta.body().get("estado").asString());
        assertEquals("35", devuelta.body().get("mensajes").get(0).get("identificador").asString());

        // Listado y XML
        assertEquals(3, llamar("GET", "/sri/comprobantes?estado=AUTORIZADO", userA, null).body().get("total").asLong());
        var xml = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sri/comprobantes/" + claveAcceso + "/xml"))
                .header("Authorization", "Bearer " + userA).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("<autorizacion/>", xml.body());

        // Concurrencia: 20 emisiones simultáneas nunca repiten secuencial
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        var secuenciales = new HashSet<String>();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Resp>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) fs.add(ex.submit(() -> llamar("POST", "/sri/comprobantes", userA, factura())));
            for (var f : fs) {
                Resp r = f.get();
                assertEquals(201, r.status(), String.valueOf(r.body()));
                secuenciales.add(r.body().get("secuencial").asString());
            }
        }
        assertEquals(20, secuenciales.size());
    }

    @Test
    void refreshTokenRotaYDetectaReuso() throws Exception {
        JsonNode t1 = login("root@test.ec", "SuperClave-123456");
        Resp r2 = llamar("POST", "/auth/refresh", null, Map.of("refreshToken", t1.get("refreshToken").asString()));
        assertEquals(200, r2.status());
        // Reusar el token viejo (como haría un atacante) revoca toda la familia, incluido el nuevo.
        assertEquals(401, llamar("POST", "/auth/refresh", null, Map.of("refreshToken", t1.get("refreshToken").asString())).status());
        assertEquals(401, llamar("POST", "/auth/refresh", null, Map.of("refreshToken", r2.body().get("refreshToken").asString())).status());
    }

    @Test
    void seguridadBasica() throws Exception {
        assertEquals(401, llamar("GET", "/emisores", null, null).status());
        assertEquals(401, llamar("GET", "/emisores", "token.falso.x", null).status());
        assertEquals(200, llamar("GET", "/status", null, null).status());
        for (int i = 0; i < 5; i++) llamar("POST", "/auth/login", null, Map.of("email", "nadie@test.ec", "password", "x"));
        assertEquals(429, llamar("POST", "/auth/login", null, Map.of("email", "nadie@test.ec", "password", "x")).status());
        assertThrows(Exception.class, () -> db.sql("delete from auditoria").update(), "auditoría inmutable");
    }
}
