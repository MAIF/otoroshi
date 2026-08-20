# Redis TLS test material

Used by `functional.LettuceTlsDatastoreSpec` to boot a redis that only accepts TLS connections
presenting a client certificate. The server certificate carries `DNS:localhost` and
`IP:127.0.0.1` as SANs so that the hostname verification testcontainers exposes actually passes.

Regenerate with:

```sh
openssl req -x509 -newkey rsa:2048 -sha256 -days 36500 -nodes \
  -keyout ca-key.pem -out ca-cert.pem \
  -subj "/C=FR/O=Otoroshi Test/CN=Otoroshi Redis Test CA"

openssl req -newkey rsa:2048 -nodes -keyout server-key.pem -out server.csr \
  -subj "/C=FR/O=Otoroshi Test/CN=localhost"
openssl x509 -req -in server.csr -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -days 36500 -sha256 -out server-cert.pem \
  -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth")

openssl req -newkey rsa:2048 -nodes -keyout client-key.pem -out client.csr \
  -subj "/C=FR/O=Otoroshi Test/CN=otoroshi-redis-client"
openssl x509 -req -in client.csr -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -days 36500 -sha256 -out client-cert.pem \
  -extfile <(printf "extendedKeyUsage=clientAuth")

rm -f server.csr client.csr ca-cert.srl && chmod 644 *.pem
```
