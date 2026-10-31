package ec.facturacion.sri.seguridad;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Usuario autenticado, sacado del JWT. */
public record Sesion(long usuarioId, Long tenantId, String rol) {

    public static Sesion de(Jwt jwt) {
        Number t = jwt.getClaim("tenant");
        return new Sesion(Long.parseLong(jwt.getSubject()), t == null ? null : t.longValue(), jwt.getClaimAsString("rol"));
    }

    /** Tenant obligatorio: los datos de facturación siempre pertenecen a una empresa. */
    public long tenant() {
        if (tenantId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operación disponible solo para usuarios de un tenant");
        return tenantId;
    }
}
