package otoroshi.ssl;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.bc.BcPEMDecryptorProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder;
import play.api.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CertInfo {

    static public PrivateKey stringToPrivateKey(String s, String password)
            throws IOException, PKCSException {
        Provider bc = otoroshi.ssl.DynamicSSLEngineProvider$.MODULE$.bouncyCastleProvider();
        PrivateKeyInfo pki;
        try (PEMParser pemParser = new PEMParser(new StringReader(s))) {
            Object o = pemParser.readObject();
            if (o instanceof PKCS8EncryptedPrivateKeyInfo) {
                PKCS8EncryptedPrivateKeyInfo epki = (PKCS8EncryptedPrivateKeyInfo) o;
                JcePKCSPBEInputDecryptorProviderBuilder builder =
                        new JcePKCSPBEInputDecryptorProviderBuilder().setProvider(bc);
                InputDecryptorProvider idp = builder.build(password.toCharArray());
                pki = epki.decryptPrivateKeyInfo(idp);
            } else if (o instanceof PEMEncryptedKeyPair) {
                PEMEncryptedKeyPair epki = (PEMEncryptedKeyPair) o;
                PEMKeyPair pkp = epki.decryptKeyPair(new BcPEMDecryptorProvider(password.toCharArray()));
                pki = pkp.getPrivateKeyInfo();
            } else {
                throw new PKCSException("Invalid encrypted private key class: " + o.getClass().getName());
            }
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(bc);
            return converter.getPrivateKey(pki);
        }
    }

    public static List<String> getSubjectAlternativeNames(String name, X509Certificate certificate, Logger log) {
        List<String> identities = new ArrayList<String>();
        try {
            Collection<List<?>> altNames;
            try {
                altNames = certificate.getSubjectAlternativeNames();
            } catch (java.security.cert.CertificateParsingException ex) {
                altNames = null;
            }
            if (altNames == null)
                return Collections.emptyList();
            for (List item : altNames) {
                Integer type = (Integer) item.get(0);
                if (type == 0 || type == 2 || type == 7) {
                    try {
                        ASN1InputStream decoder=null;
                        if(item.toArray()[1] instanceof byte[])
                            decoder = new ASN1InputStream((byte[]) item.toArray()[1]);
                        else if(item.toArray()[1] instanceof String)
                            identities.add( (String) item.toArray()[1] );
                        if(decoder==null) continue;
                        ASN1Encodable encoded = decoder.readObject();
                        encoded = ((DERSequence) encoded).getObjectAt(1);
                        encoded = ((DERTaggedObject) encoded).getExplicitBaseTagged();
                        encoded = ((DERTaggedObject) encoded).getExplicitBaseTagged();
                        String identity = ((DERUTF8String) encoded).getString();
                        identities.add(identity);
                    } catch (Exception e) {
                        log.underlyingLogger().error("Error decoding subjectAltName" + e.getLocalizedMessage(),e);
                    }
                } else {
                    log.underlyingLogger().warn("SubjectAltName of invalid type found (" + certificate.getSubjectDN().getName() + ": " + type + "): ");// + certificate);
                }
            }
        } catch (Exception e) {
            log.underlyingLogger().error("Error parsing SubjectAltName in certificate: " + name + "\r\nerror:" + e.getLocalizedMessage(),e);
        }
        return identities;
    }
}
