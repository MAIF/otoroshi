# Otoroshi's PKI

With Otoroshi, you can add your own certificates, your own CA and even create self signed certificates or certificates from CAs. You can enable auto renewal of thoses self signed certificates or certificates generated. Certificates have to be created with the certificate chain and the private key in PEM format.

An Otoroshi instance always starts with 5 auto-generated certificates. 

The highest certificate is the **Otoroshi Default Root CA Certificate**. This certificate is used by Otoroshi to sign the intermediate CA.

**Otoroshi Default Intermediate CA Certificate**: first intermediate CA that must be used to issue new certificates in Otoroshi. Creating certificates directly from the CA root certificate increases the risk of root certificate compromise, and if the CA root certificate is compromised, the entire trust infrastructure built by the SSL provider will fail

This intermediate CA signed three certificates :

*  **Otoroshi Default Client certificate**: 
*  **Otoroshi Default Jwt Signing Keypair**: default keypair (composed of a public and private key), exposed on `https://xxxxxx/.well-known/jwks.json`, that can be used to sign and verify JWT verifier
*  **Otoroshi Default Wildcard Certificate**: this certificate has `*.oto.tools` as common name. It can be very useful to the development phase

## The PKI API

link to swagger section: https://maif.github.io/otoroshi/swagger-ui/index.html#/pki

```
POST    /api/pki/certs/_letencrypt  
POST    /api/pki/certs/_p12         
POST    /api/pki/certs/_valid       
POST    /api/pki/certs/_data        
POST    /api/pki/certs              
POST    /api/pki/csrs               
POST    /api/pki/keys               
POST    /api/pki/cas                
POST    /api/pki/cas/:ca/certs/_sign
POST    /api/pki/cas/:ca/certs      
POST    /api/pki/cas/:ca/cas  
```      

@@@ warning
TODO: This section is being written, thanks for your patience :)
@@@

## The PKI UI

@@@ warning
TODO: This section is being written, thanks for your patience :)
@@@

## Exposed public keys

https://xxxxxxxxx.xxxxxxx.xx/.well-known/otoroshi/security/jwks.json
https://otoroshi-api.xxxxxxx.xx/.well-known/jwks.json

@@@ warning
TODO: This section is being written, thanks for your patience :)
@@@

## OCSP Responder

Otoroshi is able to revocate a certificate, directly from the UI, and to add a revocation status to specifiy the reason. The revocation reason can be :

* `VALID`: The certificate is not revoked
* `UNSPECIFIED`: Can be used to revoke certificates for reasons other than the specific codes.
* `KEY_COMPROMISE`: It is known or suspected that the subject's private key or other aspects have been compromised.
* `CA_COMPROMISE`: It is known or suspected that the subject's private key or other aspects have been compromised.
* `AFFILIATION_CHANGED`: The subject's name or other information in the certificate has been modified but there is no cause to suspect that the private key has been compromised.
* `SUPERSEDED`: The certificate has been superseded but there is no cause to suspect that the private key has been compromised
* `CESSATION_OF_OPERATION`: The certificate is no longer needed for the purpose for which it was issued but there is no cause to suspect that the private key has been compromised
* `CERTIFICATE_HOLD`: The certificate is temporarily revoked but there is no cause to suspect that the private kye has been compromised
* `REMOVE_FROM_CRL`: The certificate has been unrevoked
* `PRIVILEGE_WITH_DRAWN`: The certificate was revoked because a privilege contained within that certificate has been withdrawn
* `AA_COMPROMISE`: It is known or suspected that aspects of the AA validated in the attribute certificate, have been compromised

Otoroshi supports the Online Certificate Status Protocol for obtaining the revocation status of its certificates. The OCSP endpoint is also add to any generated certificate. This endpoint is available at `https://otoroshi-api.xxxxxx/.well-known/otoroshi/security/ocsp`

## A.I.A : Authority Information Access

Otoroshi provides a way to add the A.I.A in the certificate. This certificate extension contains :

* Information about how to get the issuer of this certificate (CA issuer access method)
* Address of the OCSP responder from where revocation of this certificate can be checked (OCSP access method)

`https://xxxxxxxxxx/.well-known/otoroshi/security/certificates/:cert-id`