package ec.facturacion.sri;

import ec.facturacion.sri.sri.SriClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/** Mejoras pedidas por el panel y el sistema de facturación. */
class MejorasApiTest extends IntegracionBase {
    static final String CLAVE = "Clave-segura-123";
    Empresa emp;

    @BeforeEach
    void preparar() throws Exception {
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        doReturn(new SriClient.Autorizacion("AUTORIZADO", "123", "2026-10-31T10:00:00-05:00", List.of(), "<autorizacion/>"))
                .when(sri).consultar(anyString());
        emp = nuevaEmpresa();
    }

    Map<String, Object> facturaSimple() {
        return Map.of("ruc", emp.ruc(), "establecimiento", "002", "puntoEmision", "003",
                "comprador", Map.of("tipoIdentificacion", "05", "identificacion", "0926789017", "razonSocial", "JUAN PEREZ"),
                "items", List.of(Map.of("descripcion", "Servicio", "cantidad", 1, "precioUnitario", 100, "iva", "4")),
                "pagos", List.of(Map.of("formaPago", "20")));
    }

    JsonNode usuario(String token, String prefijoEmail) throws Exception {
        for (JsonNode u : llamar("GET", "/usuarios", token, null).body())
            if (u.get("email").asString().startsWith(prefijoEmail)) return u;
        return null;
    }

    @Test
    void webhookYRespuestasTraenIdempotenciaTotalesYReceptor() throws Exception {
        assertEquals(201, llamar("POST", "/webhooks", emp.admin(), Map.of("url", "http://127.0.0.1:9/hook")).status());
        Resp r = llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple(), "Idempotency-Key", "venta-42");
        assertEquals("115.00", r.body().get("total").decimalValue().toPlainString());
        assertEquals("15.00", r.body().get("iva").decimalValue().toPlainString());
        assertEquals("JUAN PEREZ", r.body().get("receptorRazonSocial").asString());
        assertEquals("venta-42", r.body().get("idempotencyKey").asString());
        assertEquals("100.00", llamar("GET", "/sri/comprobantes", emp.user(), null).body().get("items").get(0).get("subtotal").decimalValue().toPlainString());

        String payload = db.sql("select payload::text from webhook_entrega where clave_acceso = ?")
                .param(r.body().get("claveAcceso").asString()).query(String.class).single();
        JsonNode p = json.readTree(payload);
        assertEquals("venta-42", p.get("idempotencyKey").asString());
        assertEquals(115.0, p.get("total").asDouble());
        assertEquals("002", p.get("establecimiento").asString());
        assertEquals("003", p.get("puntoEmision").asString());
        assertTrue(p.get("fechaEmision").asString().matches("\\d{4}-\\d{2}-\\d{2}"), p.toString());

        var cap = llamar("GET", "/sri/capacidades", emp.user(), null).body();
        assertFalse(cap.get("email").asBoolean(), "sin SMTP_HOST el email no está disponible");
        assertEquals("PRUEBAS", cap.get("ambiente").asString());
    }

    @Test
    void erroresDeValidacionDicenElCampo() throws Exception {
        Resp r = llamar("POST", "/emisores", emp.admin(), Map.of("ruc", "123", "razonSocial", "X", "dirMatriz", "Y"));
        assertEquals(400, r.status());
        assertEquals("ruc", r.body().get("errores").get(0).get("campo").asString());
        assertTrue(r.body().get("detail").asString().contains("ruc"));
    }

    @Test
    void gestionDeUsuarios() throws Exception {
        JsonNode user = usuario(emp.admin(), "u");
        long uid = user.get("id").asLong();
        String email = user.get("email").asString();
        Empresa otra = nuevaEmpresa();

        assertTrue(llamar("GET", "/usuarios", emp.admin(), null).body().size() == 2, "ADMIN ve solo su tenant");
        assertEquals(404, llamar("PATCH", "/usuarios/" + uid, otra.admin(), Map.of("activo", false)).status());
        long yo = usuario(emp.admin(), "a").get("id").asLong();
        assertEquals(400, llamar("PATCH", "/usuarios/" + yo, emp.admin(), Map.of("activo", false)).status());
        assertEquals(403, llamar("GET", "/usuarios", emp.user(), null).status());

        // Desactivar corta login y refresh
        String refresh = login(email, CLAVE).get("refreshToken").asString();
        assertEquals(200, llamar("PATCH", "/usuarios/" + uid, emp.admin(), Map.of("activo", false)).status());
        assertEquals(401, llamar("POST", "/auth/login", null, Map.of("email", email, "password", CLAVE)).status());
        assertEquals(401, llamar("POST", "/auth/refresh", null, Map.of("refreshToken", refresh)).status());
        assertEquals(200, llamar("PATCH", "/usuarios/" + uid, emp.admin(), Map.of("activo", true, "rol", "ADMIN")).status());
        assertEquals("ADMIN", usuario(emp.admin(), "u").get("rol").asString());

        // Reseteo de clave
        assertEquals(204, llamar("POST", "/usuarios/" + uid + "/clave", emp.admin(), Map.of("nueva", "Nueva-Clave-9876")).status());
        assertEquals(401, llamar("POST", "/auth/login", null, Map.of("email", email, "password", CLAVE)).status());
        assertEquals(200, llamar("POST", "/auth/login", null, Map.of("email", email, "password", "Nueva-Clave-9876")).status());

        // Clave actual incorrecta: 400, no 401
        assertEquals(400, llamar("PATCH", "/auth/change-password", emp.admin(), Map.of("actual", "mala", "nueva", "Otra-Clave-12345")).status());
    }

    @Test
    void desactivarTenantEmisorYPunto() throws Exception {
        String root = access("root@test.ec", "SuperClave-123456");
        long tenant = usuario(emp.admin(), "a").get("tenantId").asLong();
        String emailAdmin = usuario(emp.admin(), "a").get("email").asString();

        assertEquals(200, llamar("PATCH", "/tenants/" + tenant, root, Map.of("activo", false)).status());
        assertEquals(401, llamar("POST", "/auth/login", null, Map.of("email", emailAdmin, "password", CLAVE)).status());
        assertEquals(200, llamar("PATCH", "/tenants/" + tenant, root, Map.of("activo", true, "nombre", "Renombrado")).status());
        assertEquals(200, llamar("POST", "/auth/login", null, Map.of("email", emailAdmin, "password", CLAVE)).status());

        Resp e = llamar("PATCH", "/emisores/" + emp.emisorId(), emp.admin(), Map.of("razonSocial", "NUEVA RAZON", "contribuyenteEspecial", ""));
        assertEquals("NUEVA RAZON", e.body().get("razonSocial").asString());
        assertTrue(e.body().get("contribuyenteEspecial").isNull(), "\"\" borra el campo");
        assertEquals(403, llamar("PATCH", "/emisores/" + emp.emisorId(), emp.user(), Map.of("activo", false)).status());

        llamar("PATCH", "/emisores/" + emp.emisorId(), emp.admin(), Map.of("activo", false));
        assertEquals(400, llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple()).status());
        llamar("PATCH", "/emisores/" + emp.emisorId(), emp.admin(), Map.of("activo", true));
        assertEquals(201, llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple()).status());

        JsonNode punto = llamar("GET", "/emisores/" + emp.emisorId() + "/puntos-emision", emp.user(), null).body().get(0);
        assertTrue(punto.get("ultimosSecuenciales").isObject());
        assertEquals(1, punto.get("ultimosSecuenciales").get("01").asInt());
        long pid = punto.get("id").asLong();
        assertEquals(200, llamar("PATCH", "/emisores/" + emp.emisorId() + "/puntos-emision/" + pid, emp.admin(), Map.of("activo", false)).status());
        assertEquals(400, llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple()).status());
    }

    @Test
    void pausarWebhookYRotarSecreto() throws Exception {
        Resp w = llamar("POST", "/webhooks", emp.admin(), Map.of("url", "http://127.0.0.1:9/hook"));
        long id = w.body().get("id").asLong();
        assertFalse(llamar("PATCH", "/webhooks/" + id, emp.admin(), Map.of("activo", false)).body().get("activo").asBoolean());

        String clave = llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple()).body().get("claveAcceso").asString();
        Thread.sleep(500);
        String sql = "select intentos from webhook_entrega where clave_acceso = ?";
        assertEquals(0, db.sql(sql).param(clave).query(Integer.class).single(), "pausado: no se intenta entregar");

        llamar("PATCH", "/webhooks/" + id, emp.admin(), Map.of("activo", true));
        for (int i = 0; i < 50 && db.sql(sql).param(clave).query(Integer.class).single() == 0; i++) Thread.sleep(100);
        assertEquals(1, db.sql(sql).param(clave).query(Integer.class).single(), "al reanudar se intenta (el puerto 9 rechaza)");

        String nuevo = llamar("POST", "/webhooks/" + id + "/rotar-secreto", emp.admin(), null).body().get("secreto").asString();
        assertTrue(nuevo.startsWith("whsec_"));
        assertNotEquals(w.body().get("secreto").asString(), nuevo);
        assertEquals(400, llamar("PATCH", "/webhooks/" + id, emp.admin(), Map.of("eventos", List.of())).status());
    }

    HttpResponse<String> post(String ruta, String cuerpo, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + ruta)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String cookie(HttpResponse<String> r) {
        String set = r.headers().firstValue("Set-Cookie").orElseThrow();
        return set.substring(0, set.indexOf(';'));
    }

    @Test
    void refreshEnCookieHttpOnly() throws Exception {
        String email = usuario(emp.admin(), "a").get("email").asString();
        var login = post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + CLAVE + "\"}", "X-Token-Cookie", "true");
        assertEquals(200, login.statusCode());
        assertTrue(json.readTree(login.body()).get("refreshToken").isNull(), "el JS no recibe el refresh token");
        String set = login.headers().firstValue("Set-Cookie").orElseThrow();
        for (String atributo : List.of("sri_refresh=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/auth"))
            assertTrue(set.contains(atributo), set);

        var r1 = post("/auth/refresh", "", "Cookie", cookie(login));
        assertEquals(200, r1.statusCode());
        assertTrue(json.readTree(r1.body()).get("accessToken").asString().length() > 20);
        assertEquals(401, post("/auth/refresh", "", "Cookie", cookie(login)).statusCode(), "la cookie vieja ya no sirve");
        assertEquals(401, post("/auth/refresh", "", "Cookie", cookie(r1)).statusCode(), "reuso: se revoca toda la sesión");

        var otra = post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + CLAVE + "\"}", "X-Token-Cookie", "true");
        var salir = post("/auth/logout", "", "Cookie", cookie(otra));
        assertTrue(salir.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertEquals(401, post("/auth/refresh", "", "Cookie", cookie(otra)).statusCode());
        assertEquals(401, post("/auth/refresh", "").statusCode(), "sin cookie ni cuerpo");
    }
}
