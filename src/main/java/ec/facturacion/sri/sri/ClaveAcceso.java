package ec.facturacion.sri.sri;

import java.security.SecureRandom;

/** Clave de acceso de 49 dígitos según la ficha técnica del SRI. */
public final class ClaveAcceso {
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClaveAcceso() {}

    /** fecha en formato dd/mm/aaaa, tal como va en el XML. */
    public static String generar(String fecha, String codDoc, String ruc, String ambiente,
                                 String estab, String ptoEmi, String secuencial) {
        String codigoNumerico = String.format("%08d", RANDOM.nextInt(100_000_000));
        String base = fecha.replace("/", "") + codDoc + ruc + ambiente + estab + ptoEmi
                + secuencial + codigoNumerico + "1"; // 1 = emisión normal
        if (!base.matches("\\d{48}")) throw new IllegalArgumentException("Datos inválidos para la clave de acceso: " + base);
        return base + digitoVerificador(base);
    }

    /** Módulo 11 con pesos 2..7 de derecha a izquierda. */
    static int digitoVerificador(String numero) {
        int suma = 0, peso = 2;
        for (int i = numero.length() - 1; i >= 0; i--) {
            suma += (numero.charAt(i) - '0') * peso;
            peso = peso == 7 ? 2 : peso + 1;
        }
        int d = 11 - suma % 11;
        return d == 11 ? 0 : d == 10 ? 1 : d;
    }
}
