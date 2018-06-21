export PW="ouhUpHgmowd2xXz3"

rm -f ./foobar*
rm -f ./foo.bar*

# Generate Certificate Authority to sign certificate
keytool -genkeypair -v \
  -alias foobar \
  -dname "CN=foobarCA, OU=FooBar Org, O=FooBar Company, L=Poitiers, ST=Vienne, C=FR" \
  -keystore foobar.jks \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 4096 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true" \
  -validity 9999

# Export the foobar public certificate as foobar.crt so that it can be used in trust stores.
keytool -export -v \
  -alias foobar \
  -file foobar.crt \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foobar.jks \
  -rfc

# Create a server certificate, tied to foo.bar
keytool -genkeypair -v \
  -alias foo.bar \
  -dname "CN=foo.bar, OU=FooBar Org, O=FooBar Company, L=Poitiers, ST=Vienne, C=FR" \
  -keystore foo.bar.jks \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 2048 \
  -validity 385

# Create a certificate signing request for foo.bar
keytool -certreq -v \
  -alias foo.bar \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foo.bar.jks \
  -file foo.bar.csr

# Tell foobar to sign the foo.bar certificate. Note the extension is on the request, not the
# original certificate.
# Technically, keyUsage should be digitalSignature for DHE or ECDHE, keyEncipherment for RSA.
keytool -gencert -v \
  -alias foobar \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foobar.jks \
  -infile foo.bar.csr \
  -outfile foo.bar.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:foo.bar" \
  -rfc

keytool -gencert -v \
  -alias foobar \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foobar.jks \
  -infile foo.bar.csr \
  -outfile foo.bar-cert.pem \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:foo.bar" \
  -rfc

# Tell foo.bar.jks it can trust foobar as a signer.
keytool -import -v \
  -alias foobar \
  -file foobar.crt \
  -keystore foo.bar.jks \
  -storetype JKS \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into foo.bar.jks
keytool -import -v \
  -alias foo.bar \
  -file foo.bar.crt \
  -keystore foo.bar.jks \
  -storetype JKS \
  -storepass:env PW

# List out the contents of foo.bar.jks just to confirm it.
# If you are using Play as a TLS termination point, this is the key store you should present as the server.
keytool -list -v \
  -keystore foo.bar.jks \
  -storepass:env PW

keytool -export -v \
  -alias foo.bar \
  -file foo.bar.crt \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foo.bar.jks \
  -rfc

# Create a PKCS#12 keystore containing the public and private keys.
keytool -importkeystore -v \
  -srcalias foo.bar \
  -srckeystore foo.bar.jks \
  -srcstoretype jks \
  -srcstorepass:env PW \
  -destkeystore foo.bar.p12 \
  -destkeypass:env PW \
  -deststorepass:env PW \
  -deststoretype PKCS12

# Export the foo.bar private key for use in nginx.  Note this requires the use of OpenSSL.
openssl pkcs12 \
  -nocerts \
  -nodes \
  -passout env:PW \
  -passin env:PW \
  -in foo.bar.p12 \
  -out foo.bar.key

openssl pkcs12 \
  -nocerts \
  -nodes \
  -passout env:PW \
  -passin env:PW \
  -in foo.bar.p12 \
  -out foo.bar-key.pem

# openssl pkcs12 -in foo.bar.p12 -out foo.bar.pem -passin env:PW -passout env:PW
