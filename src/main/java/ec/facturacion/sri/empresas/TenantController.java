package ec.facturacion.sri.empresas;

import ec.facturacion.sri.seguridad.Auditoria;
import ec.facturacion.sri.seguridad.Sesion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tenants")
@PreAuthorize("hasRole('SUPERADMIN')")
public class TenantController {
    private final JdbcClient db;
    private final Auditoria auditoria;

    public TenantController(JdbcClient db, Auditoria auditoria) {
        this.db = db;
        this.auditoria = auditoria;
    }

    public record Nuevo(@NotBlank String nombre) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Nuevo req) {
        long id = db.sql("insert into tenant (nombre) values (?) returning id").param(req.nombre()).query(Long.class).single();
        auditoria.registrar(Sesion.de(jwt), "TENANT_CREADO", Map.of("tenantId", id));
        return Map.of("id", id, "nombre", req.nombre());
    }

    @GetMapping
    public List<Map<String, Object>> listar() {
        return db.sql("select id, nombre, activo, creado_en from tenant order by id").query().listOfRows();
    }
}
