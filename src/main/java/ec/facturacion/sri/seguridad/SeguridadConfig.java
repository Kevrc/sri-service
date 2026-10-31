package ec.facturacion.sri.seguridad;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SeguridadConfig {

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("rol");
        roles.setAuthorityPrefix("ROLE_");
        var conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(roles);

        // API sin cookies ni sesiones: CSRF no aplica a un Bearer token.
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/auth/login", "/auth/refresh", "/status", "/error").permitAll()
                        // Swagger UI (se apaga en producción con SWAGGER_ENABLED=false)
                        .requestMatchers("/api", "/api/**", "/api-docs", "/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv)))
                .build();
    }

    @Bean
    SecretKey claveJwt(@Value("${app.jwt-secret}") String secreto) {
        byte[] b = secreto.getBytes(StandardCharsets.UTF_8);
        if (b.length < 32) throw new IllegalStateException("JWT_SECRET debe tener al menos 32 caracteres");
        return new SecretKeySpec(b, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey k) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(k));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey k) {
        return NimbusJwtDecoder.withSecretKey(k).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** Crea el SUPERADMIN inicial desde variables de entorno (no hay credenciales por defecto). */
    @Bean
    ApplicationRunner superadminInicial(JdbcClient db, PasswordEncoder pe,
                                        @Value("${app.superadmin-email:}") String email,
                                        @Value("${app.superadmin-password:}") String password) {
        return args -> {
            boolean existe = db.sql("select exists (select 1 from usuario where rol = 'SUPERADMIN')").query(Boolean.class).single();
            if (existe || email.isBlank()) return;
            if (password.length() < 12) throw new IllegalStateException("SUPERADMIN_PASSWORD debe tener al menos 12 caracteres");
            db.sql("insert into usuario (email, password_hash, rol) values (lower(?), ?, 'SUPERADMIN')")
                    .params(email, pe.encode(password)).update();
        };
    }
}
