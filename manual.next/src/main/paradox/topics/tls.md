# TLS

as you might have understand, otoroshi can store TLS certificates and use them dynamically. It means that once a certificate is imported or created in otoroshi, you can immediately use it to serve http request over TLS, to call https backends that requires mTLS or that do not have certicates signed by a globally knowned authority.

## TLS termination



### TLS termination settings

### Certificates auto generation

## Backends TLS and mTLS calls

For any call to a backend, it is possible to customize the TLS behavior 

@@@ div { .centered-img }
<img src="../imgs/tls-call-settings.png" />
@@@

here you can define your level of trust (trust all, loose verification) or even select on or more CAs you will trust for the following backend calls. You can also select the client certificate that will be used for the following backend calls

## Keypair for signing and verification

It is also possible to use the keypair contained in a certificate to sign and verificate JWT token signature. You can mark an existing certificate in otoroshi as a keypair using the `keypair` on the certificate page.

@@@ div { .centered-img }
<img src="../imgs/jwt-token-keypair-validation.png" />
@@@
