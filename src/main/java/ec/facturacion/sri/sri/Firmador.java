package ec.facturacion.sri.sri;

import org.w3c.dom.Document;
import xades4j.algorithms.EnvelopedSignatureTransform;
import xades4j.production.DataObjectReference;
import xades4j.production.SignatureAlgorithms;
import xades4j.production.SignedDataObjects;
import xades4j.production.XadesBesSigningProfile;
import xades4j.production.XadesSigningProfile;
import xades4j.providers.impl.DirectKeyingDataProvider;

import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Firma XAdES-BES enveloped con el .p12 del emisor (RSA-SHA1, según ficha técnica del SRI). Todo en memoria. */
public final class Firmador {
    public final X509Certificate certificado;
    private final XadesSigningProfile perfil;

    public Firmador(byte[] p12, char[] clave) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(p12), clave);
            String alias = null;
            // Algunos .p12 (ej. BCE) traen varios certificados: usar el de firma digital.
            for (String a : Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(a)) continue;
                boolean[] usos = ((X509Certificate) ks.getCertificate(a)).getKeyUsage();
                if (alias == null || (usos != null && usos[0])) alias = a;
            }
            if (alias == null) throw new IllegalArgumentException("El .p12 no contiene una clave privada");
            certificado = (X509Certificate) ks.getCertificate(alias);
            PrivateKey key = (PrivateKey) ks.getKey(alias, clave);
            perfil = new XadesBesSigningProfile(new DirectKeyingDataProvider(certificado, key))
                    .withSignatureAlgorithms(new SignatureAlgorithms()
                            .withSignatureAlgorithm("RSA", SignatureMethod.RSA_SHA1)
                            .withDigestAlgorithmForDataObjectReferences(DigestMethod.SHA1)
                            .withDigestAlgorithmForReferenceProperties(DigestMethod.SHA1));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("No se pudo abrir el certificado .p12 (¿clave incorrecta?)");
        }
    }

    public void firmar(Document xml) throws Exception {
        var datos = new SignedDataObjects(new DataObjectReference("#comprobante")
                .withTransform(new EnvelopedSignatureTransform()));
        perfil.newSigner().sign(datos, xml.getDocumentElement());
    }
}
