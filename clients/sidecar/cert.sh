rm -rf ./certs
mkdir ./certs

echo '[ v3_ca ]' >> ./certs/root.cnf
echo 'subjectKeyIdentifier = hash' >> ./certs/root.cnf
echo 'authorityKeyIdentifier = keyid:always,issuer' >> ./certs/root.cnf
echo 'basicConstraints = critical, CA:true' >> ./certs/root.cnf
echo 'keyUsage = critical, digitalSignature, cRLSign, keyCertSign' >> ./certs/root.cnf
echo ''
echo '[ v3_intermediate_ca ]' >> ./certs/root.cnf
echo 'subjectKeyIdentifier = hash' >> ./certs/root.cnf
echo 'authorityKeyIdentifier = keyid:always,issuer' >> ./certs/root.cnf
echo 'basicConstraints = critical, CA:true, pathlen:0' >> ./certs/root.cnf
echo 'keyUsage = critical, digitalSignature, cRLSign, keyCertSign' >> ./certs/root.cnf

# create a certificate authority key, use password as pass phrase
openssl genrsa -out ./certs/ca.key 4096
# remove pass phrase
openssl rsa -in ./certs/ca.key -out ./certs/ca.key
# generate the certificate authority csr
openssl req -new -key ./certs/ca.key -sha256 -out ./certs/ca.csr -subj "/CN=root"
# generate the certificate authority cert
openssl x509 -req -days 3650 -sha256 -in ./certs/ca.csr -signkey ./certs/ca.key -CAcreateserial -extfile ./certs/root.cnf -extensions v3_ca -out ./certs/ca.cer

# create a certificate authority key, use password as pass phrase
openssl genrsa -out ./certs/subca.key 4096
# remove pass phrase
openssl rsa -in ./certs/subca.key -out ./certs/subca.key
# generate the csr for the certificate
openssl req -new -key ./certs/subca.key -sha256 -out ./certs/subca.csr -subj "/CN=subca"
# generate the certificate authority cert
openssl x509 -req -days 3650 -sha256 -in ./certs/subca.csr -CA ./certs/ca.cer -CAkey ./certs/ca.key -CAcreateserial -extfile ./certs/root.cnf -extensions v3_intermediate_ca -out ./certs/subca.cer
cat ./certs/subca.cer >> ./certs/_subca.cer
cat ./certs/ca.cer >> ./certs/_subca.cer
rm ./certs/subca.cer
mv ./certs/_subca.cer ./certs/subca.cer

# now create the backend cert key, use password as pass phrase
openssl genrsa -out ./certs/backend.key 2048
# remove pass phrase
openssl rsa -in ./certs/backend.key -out ./certs/backend.key
# generate the csr for the certificate
openssl req -new -key ./certs/backend.key -sha256 -out ./certs/backend.csr -subj "/CN=api-test.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./certs/backend.csr -CA ./certs/subca.cer -CAkey ./certs/subca.key -CAcreateserial -out ./certs/backend.cer
cat ./certs/backend.cer >> ./certs/_backend.cer
cat ./certs/subca.cer >> ./certs/_backend.cer
rm ./certs/backend.cer
mv ./certs/_backend.cer ./certs/backend.cer

# now create the client cert key for backend, use password as pass phrase
openssl genrsa -out ./certs/client.key 2048
# remove pass phrase
openssl rsa -in ./certs/client.key -out ./certs/client.key
# generate the csr for the certificate
openssl req -new -key ./certs/client.key -out ./certs/client.csr -subj "/CN=api-test.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./certs/client.csr -CA ./certs/subca.cer -CAkey ./certs/subca.key -CAcreateserial -out ./certs/client.cer
cat ./certs/client.cer >> ./certs/_client.cer
cat ./certs/subca.cer >> ./certs/_client.cer
rm ./certs/client.cer
mv ./certs/_client.cer ./certs/client.cer

# now create the frontend cert key, use password as pass phrase
openssl genrsa -out ./certs/oto.key 2048
# remove pass phrase
openssl rsa -in ./certs/oto.key -out ./certs/oto.key
# generate the csr for the certificate
openssl req -new -key ./certs/oto.key -sha256 -out ./certs/oto.csr -subj "/CN=api.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./certs/oto.csr -CA ./certs/subca.cer -CAkey ./certs/subca.key -CAcreateserial -out ./certs/oto.cer
cat ./certs/oto.cer >> ./certs/_oto.cer
cat ./certs/subca.cer >> ./certs/_oto.cer
rm ./certs/oto.cer
mv ./certs/_oto.cer ./certs/oto.cer


# now create the client cert key for frontend, use password as pass phrase
openssl genrsa -out ./certs/oto-client.key 2048
# remove pass phrase
openssl rsa -in ./certs/oto-client.key -out ./certs/oto-client.key
# generate the csr for the certificate
openssl req -new -key ./certs/oto-client.key -out ./certs/oto-client.csr -subj "/CN=api.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./certs/oto-client.csr -CA ./certs/subca.cer -CAkey ./certs/subca.key -CAcreateserial -out ./certs/oto-client.cer
cat ./certs/oto-client.cer >> ./certs/_oto-client.cer
cat ./certs/subca.cer >> ./certs/_oto-client.cer
rm ./certs/oto-client.cer
mv ./certs/_oto-client.cer ./certs/oto-client.cer

rm -f .srl