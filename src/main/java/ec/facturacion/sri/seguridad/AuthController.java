package ec.facturacion.sri.seguridad;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final Duration DURACION_ACCESS = Duration.ofMinutes(15);
    private static final Duration DURACION_REFRESH = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final PasswordEncoder pe;
    private final JwtEncoder jwt;
    private final Auditoria auditoria;
    // Hash de relleno: el login tarda lo mismo exista o no el email (evita enumerar usuarios).
    private final String hashFalso;

    public AuthController(JdbcClient db, PasswordEncoder pe, JwtEncoder jwt, Auditoria auditoria) {
        this.db = db;
        this.pe = pe;
        this.jwt = jwt;
        this.auditoria = auditoria;
        this.hashFalso = pe.encode(UUID.randomUUID().toString());
    }

    public record Login(@NotBlank @Email String email, @NotBlank String password) {}
    public record Tokens(String accessToken, String refreshToken, long expiraEnSegundos) {}
    public record Refresh(@NotBlank String refreshToken) {}
    public record Registro(@NotBlank @Email String email, @Size(min = 12, max = 128) String password,
                           @Pattern(regexp = "SUPERADMIN|ADMIN|USER") String rol, Long tenantId) {}
    public record CambioClave(@NotBlank String actual, @NotBlank @Size(min = 12, max = 128) String nueva) {}

    record Usuario(long id, Long tenantId, String rol, String passwordHash, boolean activo) {}

    @PostMapping("/login")
    public Tokens login(@Valid @RequestBody Login req) {
        String email = req.email().toLowerCase();
        limite.verificar(email);
        Usuario u = db.sql("""
                        select u.id, u.tenant_id, u.rol, u.password_hash, u.activo and coalesce(t.activo, true) as activo
                        from usuario u left join tenant t on t.id = u.tenant_id where u.email = ?""")
                .param(email).query(Usuario.class).optional().orElse(null);
        boolean ok = pe.matches(req.password(), u == null ? hashFalso : u.passwordHash()) && u != null && u.activo();
        if (!ok) {
            limite.fallo(email);
            auditoria.registrar(u == null ? null : u.id(), u == null ? null : u.tenantId(), "LOGIN_FALLIDO", Map.of("email", email));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        limite.exito(email);
        auditoria.registrar(u.id(), u.tenantId(), "LOGIN", Map.of());
        return emitir(u, UUID.randomUUID());
    }

    /** Rotación: cada refresh token sirve una sola vez. Si se reutiliza, se revoca toda la familia (posible robo). */
    @PostMapping("/refresh")
    @Transactional(noRollbackFor = ResponseStatusException.class) // la revocación debe persistir aunque se responda 401
    public Tokens refresh(@Valid @RequestBody Refresh req) {
        record Fila(long id, UUID familia, Instant expiraEn, boolean usado, boolean revocado, long usuarioId) {}
        Fila f = db.sql("select id, familia, expira_en, usado, revocado, usuario_id from refresh_token where hash = ? for update")
                .param(hash(req.refreshToken())).query(Fila.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido"));
        if (f.usado() || f.revocado()) {
            db.sql("update refresh_token set revocado = true where familia = ?").param(f.familia()).update();
            auditoria.registrar(f.usuarioId(), null, "REFRESH_REUTILIZADO", Map.of("familia", f.familia().toString()));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido");
        }
        if (f.expiraEn().isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expirado");
        db.sql("update refresh_token set usado = true where id = ?").param(f.id()).update();
        Usuario u = db.sql("""
                        select u.id, u.tenant_id, u.rol, u.password_hash, u.activo and coalesce(t.activo, true) as activo
                        from usuario u left join tenant t on t.id = u.tenant_id where u.id = ?""")
                .param(f.usuarioId()).query(Usuario.class).single();
        if (!u.activo()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario inactivo");
        return emitir(u, f.familia());
    }

    @PostMapping("/logout")
    public void logout(@Valid @RequestBody Refresh req) {
        db.sql("update refresh_token set revocado = true where familia = (select familia from refresh_token where hash = ?)")
                .param(hash(req.refreshToken())).update();
    }

    /** SUPERADMIN crea usuarios en cualquier tenant; ADMIN solo en el suyo y sin crear SUPERADMIN. */
    @PostMapping("/register")
    @PreAuthorize("hasAnyRole('SUPERADMIN','ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registrar(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Registro req) {
        Sesion s = Sesion.de(token);
        Long tenant = req.tenantId();
        if (s.rol().equals("ADMIN")) {
            if (req.rol().equals("SUPERADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un ADMIN no puede crear SUPERADMIN");
            tenant = s.tenant();
        }
        if (req.rol().equals("SUPERADMIN")) tenant = null;
        else if (tenant == null) throw new IllegalArgumentException("tenantId es obligatorio");
        try {
            long id = db.sql("insert into usuario (tenant_id, email, password_hash, rol) values (?, lower(?), ?, ?) returning id")
                    .params(tenant, req.email(), pe.encode(req.password()), req.rol()).query(Long.class).single();
            auditoria.registrar(s, "USUARIO_CREADO", Map.of("usuarioId", id, "rol", req.rol()));
            return Map.of("id", id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El email ya está registrado");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("tenantId no existe");
        }
    }

    @PatchMapping("/change-password")
    @Transactional
    public void cambiarClave(@AuthenticationPrincipal Jwt token, @Valid @RequestBody CambioClave req) {
        Sesion s = Sesion.de(token);
        String actual = db.sql("select password_hash from usuario where id = ?").param(s.usuarioId()).query(String.class).single();
        if (!pe.matches(req.actual(), actual)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Clave actual incorrecta");
        db.sql("update usuario set password_hash = ? where id = ?").params(pe.encode(req.nueva()), s.usuarioId()).update();
        // Cierra todas las sesiones abiertas con la clave anterior.
        db.sql("update refresh_token set revocado = true where usuario_id = ?").param(s.usuarioId()).update();
        auditoria.registrar(s, "CLAVE_CAMBIADA", Map.of());
    }

    private Tokens emitir(Usuario u, UUID familia) {
        Instant ahora = Instant.now();
        var claims = JwtClaimsSet.builder().subject(String.valueOf(u.id())).issuedAt(ahora)
                .expiresAt(ahora.plus(DURACION_ACCESS)).claim("rol", u.rol());
        if (u.tenantId() != null) claims.claim("tenant", u.tenantId());
        String access = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();

        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        db.sql("insert into refresh_token (usuario_id, familia, hash, expira_en) values (?, ?, ?, ?)")
                .params(u.id(), familia, hash(refresh), java.sql.Timestamp.from(ahora.plus(DURACION_REFRESH))).update();
        return new Tokens(access, refresh, DURACION_ACCESS.toSeconds());
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // shortcut: límite en memoria, vale para una instancia; con varias réplicas moverlo a Postgres.
    private final Limite limite = new Limite();

    /** Máximo 5 logins fallidos por email cada 15 minutos. */
    static final class Limite {
        private final Map<String, ArrayDeque<Instant>> fallos = new ConcurrentHashMap<>();

        void verificar(String email) {
            var q = fallos.get(email);
            if (q == null) return;
            synchronized (q) {
                Instant corte = Instant.now().minus(Duration.ofMinutes(15));
                while (!q.isEmpty() && q.peekFirst().isBefore(corte)) q.pollFirst();
                if (q.size() >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos, espere 15 minutos");
            }
        }

        void fallo(String email) {
            if (fallos.size() > 100_000) fallos.clear(); // tope de memoria ante ataques con emails aleatorios
            var q = fallos.computeIfAbsent(email, k -> new ArrayDeque<>());
            synchronized (q) { q.addLast(Instant.now()); }
        }

        void exito(String email) {
            fallos.remove(email);
        }
    }
}
