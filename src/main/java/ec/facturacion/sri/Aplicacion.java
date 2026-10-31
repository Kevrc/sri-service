package ec.facturacion.sri;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;

import java.util.NoSuchElementException;

@SpringBootApplication
@EnableScheduling
public class Aplicacion {

    public static void main(String[] args) {
        SpringApplication.run(Aplicacion.class, args);
    }

    // Decimales como BigDecimal sin recortar ceros: 10.00 llega al XML como "10.00".
    @Bean
    JsonMapperBuilderCustomizer decimalesExactos() {
        return b -> b.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("API Facturación Electrónica SRI").version("1.0.0")
                        .description("Emite, firma y gestiona comprobantes electrónicos ante el SRI de Ecuador. "
                                + "Autentícate con POST /auth/login y pega el accessToken en Authorize."))
                .addSecurityItem(new SecurityRequirement().addList("jwt"))
                .components(new Components().addSecuritySchemes("jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @RestControllerAdvice
    static class Errores {
        @ExceptionHandler(IllegalArgumentException.class)
        ProblemDetail invalido(IllegalArgumentException e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // El mensaje de Jackson dice qué campo falló; el genérico "Failed to read request" no ayuda al cliente.
        @ExceptionHandler(HttpMessageNotReadableException.class)
        ProblemDetail jsonInvalido(HttpMessageNotReadableException e) {
            String causa = e.getMostSpecificCause().getMessage();
            causa = causa == null ? "" : causa.lines().findFirst().orElse("");
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "JSON inválido: " + (causa.length() > 300 ? causa.substring(0, 300) : causa));
        }

        @ExceptionHandler(NoSuchElementException.class)
        ProblemDetail noExiste(NoSuchElementException e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
