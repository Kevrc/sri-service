package ec.facturacion.sri.seguridad;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Administración de usuarios. SUPERADMIN ve a todos; ADMIN solo a los de su tenant y nunca a un SUPERADMIN.
 * Nadie se modifica a sí mismo aquí (para su clave está /auth/change-password), así no se queda sin acceso por error.
 */
@RestController
@RequestMapping("/usuarios")
@PreAuthorize("hasAnyRole('SUPERADMIN','ADMIN')")
public class UsuarioController {
    private final JdbcClient db;
    private final PasswordEncoder pe;
    private final Auditoria auditoria;

    public UsuarioController(JdbcClient db, PasswordEncoder pe, Auditoria auditoria) {
        this.db = db;
        this.pe = pe;
        this.auditoria = auditoria;
    }

    public record Cambio(Boolean activo, @Pattern(regexp = "ADMIN|USER") String rol) {}
    public record NuevaClave(@NotBlank @Size(min = 12, max = 128) String nueva) {}

    @GetMapping
    public List<Map<String, Object>> listar(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long tenantId) {
        Sesion s = Sesion.de(jwt);
        Long filtro = s.rol().equals("SUPERADMIN") ? tenantId : Long.valueOf(s.tenant());
        return db.sql("""
                        select id, email, rol, tenant_id as "tenantId", activo, creado_en as "creadoEn" from usuario
                        where (?::bigint is null or tenant_id = ?) order by id""")
                .params(filtro, filtro).query().listOfRows();
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> modificar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Cambio req) {
        Sesion s = Sesion.de(jwt);
        long tenant = objetivo(s, id);
        db.sql("update usuario set activo = coalesce(?, activo), rol = coalesce(?, rol) where id = ?")
                .params(req.activo(), req.rol(), id).update();
        // Desactivado: se cierran sus sesiones (el access token vigente expira solo en 15 minutos como máximo).
        if (Boolean.FALSE.equals(req.activo())) revocarSesiones(id);
        auditoria.registrar(s, "USUARIO_MODIFICADO", Map.of("usuarioId", id, "tenantId", tenant,
                "activo", String.valueOf(req.activo()), "rol", String.valueOf(req.rol())));
        return db.sql("select id, email, rol, tenant_id as \"tenantId\", activo from usuario where id = ?").param(id).query().singleRow();
    }

    /** Clave temporal puesta por un administrador (por ejemplo, si el usuario la olvidó). Cierra sus sesiones. */
    @PostMapping("/{id}/clave")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetearClave(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody NuevaClave req) {
        Sesion s = Sesion.de(jwt);
        objetivo(s, id);
        db.sql("update usuario set password_hash = ? where id = ?").params(pe.encode(req.nueva()), id).update();
        revocarSesiones(id);
        auditoria.registrar(s, "CLAVE_RESETEADA", Map.of("usuarioId", id));
    }

    /** Verifica que quien opera puede tocar a ese usuario; devuelve 404 (no 403) para no revelar usuarios de otros tenants. */
    private long objetivo(Sesion s, long id) {
        if (id == s.usuarioId()) throw new IllegalArgumentException("No puedes modificar tu propio usuario aquí");
        record U(Long tenantId, String rol) {}
        U u = db.sql("select tenant_id, rol from usuario where id = ?").param(id).query(U.class).optional()
                .orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
        boolean permitido = s.rol().equals("SUPERADMIN")
                ? !u.rol().equals("SUPERADMIN")
                : !u.rol().equals("SUPERADMIN") && u.tenantId() != null && u.tenantId() == s.tenant();
        if (!permitido) throw new NoSuchElementException("Usuario no encontrado");
        return u.tenantId();
    }

    private void revocarSesiones(long usuarioId) {
        db.sql("update refresh_token set revocado = true where usuario_id = ?").param(usuarioId).update();
    }
}
