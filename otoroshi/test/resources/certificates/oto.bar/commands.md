# Generate a CA
# 1. Generate CA private key
openssl genrsa -out ca-key.pem 4096

# 2. Generate CA certificate (self-signed)
openssl req -x509 -new -nodes -key ca-key.pem -sha256 -days 36500 \
  -out ca-cert.pem \
  -subj "/C=FR/ST=Paris/L=Paris/O=TestCA/OU=Dev/CN=TestCA"

# Generate server certificate for oto.bar
# 1. Générer la clé serveur
openssl genrsa -out server-key.pem 2048

# 2. Générer le CSR en utilisant ton fichier de config
openssl req -new -key server-key.pem -out server.csr -config server.conf

# 3. Signer le CSR avec ta CA
openssl x509 -req -in server.csr -CA ca-cert.pem -CAkey ca-key.pem \
  -CAcreateserial -out server-cert.pem -days 36500 -sha256 \
  -extensions v3_req -extfile server.conf

# 4. Créer le fullchain pour Otoroshi
cat server-cert.pem ca-cert.pem > server-fullchain.pem

# Generate client certificate for mTLS
# 1. Generate client key
openssl genrsa -out client-key.pem 2048

# 2. Generate CSR for client
openssl req -new -key client-key.pem -out client.csr \
  -subj "/C=FR/ST=Paris/L=Paris/O=Client/OU=Dev/CN=test-client"

# 3. Sign client CSR with CA
openssl x509 -req -in client.csr -CA ca-cert.pem -CAkey ca-key.pem \
  -CAcreateserial -out client-cert.pem -days 365 -sha256

# 4. Optional: full chain for client
cat client-cert.pem ca-cert.pem > client-fullchain.pem
