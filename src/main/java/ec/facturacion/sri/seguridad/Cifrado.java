package ec.facturacion.sri.seguridad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM para certificados y sus claves. Formato: IV (12 bytes) + texto cifrado con tag. */
@Component
public class Cifrado {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec clave;

    public Cifrado(@Value("${app.encryption-key}") String claveBase64) {
        byte[] k = Base64.getDecoder().decode(claveBase64);
        if (k.length != 32) throw new IllegalStateException("ENCRYPTION_KEY debe ser 32 bytes en base64 (openssl rand -base64 32)");
        clave = new SecretKeySpec(k, "AES");
    }

    public byte[] cifrar(byte[] datos) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(128, iv));
            return ByteBuffer.allocate(12 + datos.length + 16).put(iv).put(c.doFinal(datos)).array();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] descifrar(byte[] datos) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(128, datos, 0, 12));
            return c.doFinal(datos, 12, datos.length - 12);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descifrar (¿cambió ENCRYPTION_KEY?)", e);
        }
    }
}
