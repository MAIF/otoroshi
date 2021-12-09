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

The Otoroshi's PKI can be managed using the admin api of otoroshi (by default admin api is exposed on https://otoroshi-api.xxxxx)

Link to the complete swagger section about PKI : https://maif.github.io/otoroshi/swagger-ui/index.html#/pki

* `POST`    [/api/pki/certs/_letencrypt](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.genLetsEncryptCert): generates a certificate using Let's Encrypt or any ACME compatible system
* `POST`    [/api/pki/certs/_p12](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.importCertFromP12): import a .p12 file as client certificates
* `POST`    [/api/pki/certs/_valid](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.certificateIsValid): check if a certificate is valid (based on its own data)
* `POST`    [/api/pki/certs/_data](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.certificateData): extract data from a certificate
* `POST`    [/api/pki/certs](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.genSelfSignedCert): generates a self signed certificates
* `POST`    [/api/pki/csrs](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.genCsr) : generates a CSR
* `POST`    [/api/pki/keys](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.genKeyPair) : generates a keypair
* `POST`    [/api/pki/cas](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.genSelfSignedCA)  : generates a self signed CA@
* `POST`    [/api/pki/cas/:ca/certs/_sign](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.signCert): sign a certificate based on CSR
* `POST`    [/api/pki/cas/:ca/certs](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.genCert): generates a certificate
* `POST`    [/api/pki/cas/:ca/cas](https://maif.github.io/otoroshi/swagger-ui/index.html#/pki/otoroshi.controllers.adminapi.PkiController.genSubCA) : generates a sub-CA

## The PKI UI

All generated certificates are listed in the `https://xxxxxx/bo/dashboard/certificates` page. All those certificates can be used to serve traffic with TLS, perform mTLS calls, sign and verify JWT tokens.

The PKI UI are composed of these following actions:

* **Add item**: redirects the user on the certificate creation page. It’s useful when you already had a certificate (like a pem file) and that you want to load it in Otoroshi.
* **Let's Encrypt certificate**: asks a certificate matching a given host to Let’s encrypt
* **Create certificate**: issues a certificate with an existing Otoroshi certificate as CA. You can create a client certificate, a server certificate or a keypair certiciate that will be used to verify and sign JWT tokens.
* **Import .p12 file**: loads a p12 file as certificate

Under these buttons, you have the list of current certificates, imported or generated, revoked or not. For each certificate, you will find: 

* a **name** 
* a **description** 
* the **subject** 
* the **type** of certificate (CA / client / keypair / certificate)
* the **revoked reason** (empty if not) 
* the **creation date** following by its **expiration date**.

## Exposed public keys

The Otoroshi certificate can be turned and used as keypair (simple action that can be executed by editing a certificate or during its creation, or using the admin api). A Otoroski keypair can be used to sign and verify JWT tokens with asymetric signature. Once a jwt token is signed with a keypair, it can be necessary to provide a way to the services to verify the tokens received by Otoroshi. This usage is cover by Otoroshi by the flag `Public key exposed`, available on each certificate.

Otoroshi exposes each keypair with the flag enabled, on the following routes:

* `https://xxxxxxxxx.xxxxxxx.xx/.well-known/otoroshi/security/jwks.json`
* `https://otoroshi-api.xxxxxxx.xx/.well-known/jwks.json`

On these routes, you will find the list of public keys with the following information:

* `kid`: key ID parameter is used to match a specific key @link:[section 4 - RFC5717](https://datatracker.ietf.org/doc/html/rfc7517#section-4.5)
* `kty`: key type parameter identifies the cryptographic algorithm
   family used with the key, such as "RSA" or "EC" @link:[section 6 - RFC5717](https://datatracker.ietf.org/doc/html/rfc7517#page-6)
* `n` and `e`: RSA key values @link:[section 9.3 - RFC5717](https://datatracker.ietf.org/doc/html/rfc7517#section-9.3)


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