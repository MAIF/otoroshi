export PW="ouhUpHgmowd2xXz3"

rm -f ./foobar*
rm -f ./oto.tools*

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

# Export the foobar public certificate as foobar.cert so that it can be used in trust stores.
keytool -export -v \
  -alias foobar \
  -file foobar.cert \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foobar.jks \
  -rfc

# Create a server certificate, tied to oto.tools
keytool -genkeypair -v \
  -alias oto.tools \
  -dname "CN=*.oto.tools, OU=FooBar Org, O=FooBar Company, L=Poitiers, ST=Vienne, C=FR" \
  -keystore oto.tools.jks \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 2048 \
  -validity 385

# Create a certificate signing request for oto.tools
keytool -certreq -v \
  -alias oto.tools \
  -keypass:env PW \
  -storepass:env PW \
  -keystore oto.tools.jks \
  -file oto.tools.csr

# Tell foobar to sign the oto.tools certificate. Note the extension is on the request, not the
# original certificate.
# Technically, keyUsage should be digitalSignature for DHE or ECDHE, keyEncipherment for RSA.
keytool -gencert -v \
  -alias foobar \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foobar.jks \
  -infile oto.tools.csr \
  -outfile oto.tools.cert \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:oto.tools" \
  -rfc

keytool -gencert -v \
  -alias foobar \
  -keypass:env PW \
  -storepass:env PW \
  -keystore foobar.jks \
  -infile oto.tools.csr \
  -outfile oto.tools-cert.pem \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:oto.tools" \
  -rfc

# Tell oto.tools.jks it can trust foobar as a signer.
keytool -import -v \
  -alias foobar \
  -file foobar.cert \
  -keystore oto.tools.jks \
  -storetype JKS \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into oto.tools.jks
keytool -import -v \
  -alias oto.tools \
  -file oto.tools.cert \
  -keystore oto.tools.jks \
  -storetype JKS \
  -storepass:env PW

# List out the contents of oto.tools.jks just to confirm it.
# If you are using Play as a TLS termination point, this is the key store you should present as the server.
keytool -list -v \
  -keystore oto.tools.jks \
  -storepass:env PW

keytool -export -v \
  -alias oto.tools \
  -file oto.tools.cert \
  -keypass:env PW \
  -storepass:env PW \
  -keystore oto.tools.jks \
  -rfc

# Create a PKCS#12 keystore containing the public and private keys.
keytool -importkeystore -v \
  -srcalias oto.tools \
  -srckeystore oto.tools.jks \
  -srcstoretype jks \
  -srcstorepass:env PW \
  -destkeystore oto.tools.p12 \
  -destkeypass:env PW \
  -deststorepass:env PW \
  -deststoretype PKCS12

# Export the oto.tools private key for use in nginx.  Note this requires the use of OpenSSL.
openssl pkcs12 \
  -nocerts \
  -nodes \
  -passout env:PW \
  -passin env:PW \
  -in oto.tools.p12 \
  -out oto.tools.key

openssl pkcs12 \
  -nocerts \
  -nodes \
  -passout env:PW \
  -passin env:PW \
  -in oto.tools.p12 \
  -out oto.tools-key.pem

# openssl pkcs12 -in oto.tools.p12 -out oto.tools.pem -passin env:PW -passout env:PW