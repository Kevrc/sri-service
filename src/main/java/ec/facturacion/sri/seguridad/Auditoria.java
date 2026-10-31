package ec.facturacion.sri.seguridad;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Registro inmutable (protegido por trigger en la base) de acciones sensibles. */
@Component
public class Auditoria {
    private final JdbcClient db;
    private final JsonMapper json;

    public Auditoria(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    public void registrar(Long usuarioId, Long tenantId, String accion, Map<String, ?> detalle) {
        String ip = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest().getRemoteAddr() : null;
        db.sql("insert into auditoria (usuario_id, tenant_id, accion, detalle, ip) values (?, ?, ?, ?::jsonb, ?)")
                .params(usuarioId, tenantId, accion, json.writeValueAsString(detalle), ip).update();
    }

    public void registrar(Sesion s, String accion, Map<String, ?> detalle) {
        registrar(s.usuarioId(), s.tenantId(), accion, detalle);
    }
}
