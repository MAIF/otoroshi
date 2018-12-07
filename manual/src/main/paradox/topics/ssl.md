# SSL/TLS termination with Otoroshi

Otoroshi can be used as an SSL/TLS termination. It is enabled by default but you can customise HTTPS port with `https.port` config. and env. var `HTTPS_PORT`. You can create upload any certificate you want in the Otoroshi UI or using the API. Just go to `settings (cog icon) / SSL certificates`.

@@@ note { title="TLS 1.3 support" }
Otoroshi does support TLS 1.3 when used in combination with JDK 11

<img src="../img/tls13.png" />
@@@

@@@ div { .centered-img }
<img src="../img/ssl.png" />
@@@

Here you can add your own certificates, your own CA and even create self signed certificates or certificates from CAs. You can enable auto renewal of thoses self signed certificates or certificates generated. Certificates have to be created with the certificate chain and the private key in PEM format with no password on the private key.

You can remove the password of a key with the following command

```sh
openssl rsa -in keywithpassword.key -out keywithoutpassword.key
```

@@@ div { .centered-img }
<img src="../img/ssl2.png" />
@@@

