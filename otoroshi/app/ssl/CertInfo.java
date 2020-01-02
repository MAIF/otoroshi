package ssl;

import org.bouncycastle.asn1.*;
import play.api.Logger;

import java.io.UnsupportedEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CertInfo {

    public static List<String> getSubjectAlternativeNames(X509Certificate certificate, Logger log) {
        List<String> identities = new ArrayList<String>();
        try {
            Collection<List<?>> altNames = certificate.getSubjectAlternativeNames();
            if (altNames == null)
                return Collections.emptyList();
            for (List item : altNames) {
                Integer type = (Integer) item.get(0);
                if (type == 0 || type == 2) {
                    try {
                        ASN1InputStream decoder=null;
                        if(item.toArray()[1] instanceof byte[])
                            decoder = new ASN1InputStream((byte[]) item.toArray()[1]);
                        else if(item.toArray()[1] instanceof String)
                            identities.add( (String) item.toArray()[1] );
                        if(decoder==null) continue;
                        ASN1Encodable encoded = decoder.readObject();
                        encoded = ((DERSequence) encoded).getObjectAt(1);
                        encoded = ((DERTaggedObject) encoded).getObject();
                        encoded = ((DERTaggedObject) encoded).getObject();
                        String identity = ((DERUTF8String) encoded).getString();
                        identities.add(identity);
                    } catch (Exception e) {
                        log.underlyingLogger().error("Error decoding subjectAltName" + e.getLocalizedMessage(),e);
                    }
                } else {
                    log.underlyingLogger().warn("SubjectAltName of invalid type found: " + certificate);
                }
            }
        }
        catch (CertificateParsingException e) {
            log.underlyingLogger().error("Error parsing SubjectAltName in certificate: " + certificate + "\r\nerror:" + e.getLocalizedMessage(),e);
        }
        return identities;
    }
}
