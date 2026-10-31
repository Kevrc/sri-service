package ec.facturacion.sri.empresas;

import ec.facturacion.sri.seguridad.Auditoria;
import ec.facturacion.sri.seguridad.Sesion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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

    public record Cambio(@Size(min = 1, max = 200) String nombre, Boolean activo) {}

    /** Un tenant desactivado no puede iniciar sesión ni renovar tokens; sus sesiones se cierran. */
    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> modificar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Cambio req) {
        int n = db.sql("update tenant set nombre = coalesce(?, nombre), activo = coalesce(?, activo) where id = ?")
                .params(req.nombre(), req.activo(), id).update();
        if (n == 0) throw new NoSuchElementException("Tenant no encontrado");
        if (Boolean.FALSE.equals(req.activo()))
            db.sql("update refresh_token set revocado = true where usuario_id in (select id from usuario where tenant_id = ?)").param(id).update();
        auditoria.registrar(Sesion.de(jwt), "TENANT_MODIFICADO", Map.of("tenantId", id, "activo", String.valueOf(req.activo())));
        return db.sql("select id, nombre, activo, creado_en from tenant where id = ?").param(id).query().singleRow();
    }

    @GetMapping
    public List<Map<String, Object>> listar() {
        return db.sql("select id, nombre, activo, creado_en from tenant order by id").query().listOfRows();
    }
}
