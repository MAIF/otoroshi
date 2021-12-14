# End-to-end mTLS

If you want to use MTLS on otoroshi, you first need to enable it. It is not enabled by default as it will make TLS handshake way heavier. 
To enable it just change the following config :

```sh
otoroshi.ssl.fromOutside.clientAuth=None|Want|Need
```

or using env. variables

```sh
SSL_OUTSIDE_CLIENT_AUTH=None|Want|Need
```

You can use the `Want` setup if you cant to have both mtls on some services and no mtls on other services.

You can also change the trusted CA list sent in the handshake certificate request from the `Danger Zone` in `Tls Settings`.

Otoroshi support mutual TLS out of the box. mTLS from client to Otoroshi and from Otoroshi to targets are supported. In this article we will see how to configure Otoroshi to use end-to-end mTLS. All code and files used in this articles can be found on the [Otoroshi github](https://github.com/MAIF/otoroshi/tree/master/demos/mtls)

### Create certificates

But first we need to generate some certificates to make the demo work

```sh
mkdir mtls-demo
cd mtls-demo
mkdir ca
mkdir server
mkdir client

# create a certificate authority key, use password as pass phrase
openssl genrsa -out ./ca/ca-backend.key 4096
# remove pass phrase
openssl rsa -in ./ca/ca-backend.key -out ./ca/ca-backend.key
# generate the certificate authority cert
openssl req -new -x509 -sha256 -days 730 -key ./ca/ca-backend.key -out ./ca/ca-backend.cer -subj "/CN=MTLSB"


# create a certificate authority key, use password as pass phrase
openssl genrsa -out ./ca/ca-frontend.key 2048
# remove pass phrase
openssl rsa -in ./ca/ca-frontend.key -out ./ca/ca-frontend.key
# generate the certificate authority cert
openssl req -new -x509 -sha256 -days 730 -key ./ca/ca-frontend.key -out ./ca/ca-frontend.cer -subj "/CN=MTLSF"


# now create the backend cert key, use password as pass phrase
openssl genrsa -out ./server/_.backend.oto.tools.key 2048
# remove pass phrase
openssl rsa -in ./server/_.backend.oto.tools.key -out ./server/_.backend.oto.tools.key
# generate the csr for the certificate
openssl req -new -key ./server/_.backend.oto.tools.key -sha256 -out ./server/_.backend.oto.tools.csr -subj "/CN=*.backend.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./server/_.backend.oto.tools.csr -CA ./ca/ca-backend.cer -CAkey ./ca/ca-backend.key -set_serial 1 -out ./server/_.backend.oto.tools.cer
# verify the certificate, should output './server/_.backend.oto.tools.cer: OK'
openssl verify -CAfile ./ca/ca-backend.cer ./server/_.backend.oto.tools.cer


# now create the frontend cert key, use password as pass phrase
openssl genrsa -out ./server/_.frontend.oto.tools.key 2048
# remove pass phrase
openssl rsa -in ./server/_.frontend.oto.tools.key -out ./server/_.frontend.oto.tools.key
# generate the csr for the certificate
openssl req -new -key ./server/_.frontend.oto.tools.key -sha256 -out ./server/_.frontend.oto.tools.csr -subj "/CN=*.frontend.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./server/_.frontend.oto.tools.csr -CA ./ca/ca-frontend.cer -CAkey ./ca/ca-frontend.key -set_serial 1 -out ./server/_.frontend.oto.tools.cer
# verify the certificate, should output './server/_.frontend.oto.tools.cer: OK'
openssl verify -CAfile ./ca/ca-frontend.cer ./server/_.frontend.oto.tools.cer


# now create the client cert key for backend, use password as pass phrase
openssl genrsa -out ./client/_.backend.oto.tools.key 2048
# remove pass phrase
openssl rsa -in ./client/_.backend.oto.tools.key -out ./client/_.backend.oto.tools.key
# generate the csr for the certificate
openssl req -new -key ./client/_.backend.oto.tools.key -out ./client/_.backend.oto.tools.csr -subj "/CN=*.backend.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./client/_.backend.oto.tools.csr -CA ./ca/ca-backend.cer -CAkey ./ca/ca-backend.key -set_serial 2 -out ./client/_.backend.oto.tools.cer
# generate a pem version of the cert and key, use password as password
openssl x509 -in client/_.backend.oto.tools.cer -out client/_.backend.oto.tools.pem -outform PEM


# now create the client cert key for frontend, use password as pass phrase
openssl genrsa -out ./client/_.frontend.oto.tools.key 2048
# remove pass phrase
openssl rsa -in ./client/_.frontend.oto.tools.key -out ./client/_.frontend.oto.tools.key
# generate the csr for the certificate
openssl req -new -key ./client/_.frontend.oto.tools.key -out ./client/_.frontend.oto.tools.csr -subj "/CN=*.frontend.oto.tools"
# generate the certificate
openssl x509 -req -days 365 -sha256 -in ./client/_.frontend.oto.tools.csr -CA ./ca/ca-frontend.cer -CAkey ./ca/ca-frontend.key -set_serial 2 -out ./client/_.frontend.oto.tools.cer
# generate a pkcs12 version of the cert and key, use password as password
# openssl pkcs12 -export -clcerts -in client/_.frontend.oto.tools.cer -inkey client/_.frontend.oto.tools.key -out client/_.frontend.oto.tools.p12
openssl x509 -in client/_.frontend.oto.tools.cer -out client/_.frontend.oto.tools.pem -outform PEM
```

Once it's done, you should have something like

```sh
$ tree
.
├── backend.js
├── ca
│   ├── ca-backend.cer
│   ├── ca-backend.key
│   ├── ca-frontend.cer
│   └── ca-frontend.key
├── client
│   ├── _.backend.oto.tools.cer
│   ├── _.backend.oto.tools.csr
│   ├── _.backend.oto.tools.key
│   ├── _.backend.oto.tools.pem
│   ├── _.frontend.oto.tools.cer
│   ├── _.frontend.oto.tools.csr
│   ├── _.frontend.oto.tools.key
│   └── _.frontend.oto.tools.pem
└── server
    ├── _.backend.oto.tools.cer
    ├── _.backend.oto.tools.csr
    ├── _.backend.oto.tools.key
    ├── _.frontend.oto.tools.cer
    ├── _.frontend.oto.tools.csr
    └── _.frontend.oto.tools.key

3 directories, 18 files
```

### The backend service 

now, let's create a backend service using nodejs. Create a file named `backend.js`

```sh
touch backend.js
```

and put the following content

```js
const fs = require('fs'); 
const https = require('https'); 

const options = { 
  key: fs.readFileSync('./server/_.backend.oto.tools.key'), 
  cert: fs.readFileSync('./server/_.backend.oto.tools.cer'), 
  ca: fs.readFileSync('./ca/ca-backend.cer'), 
}; 

const server = https.createServer(options, (req, res) => { 
  res.writeHead(200, {
    'Content-Type': 'application/json'
  }); 
  res.end(JSON.stringify({ message: 'Hello World!' }) + "\n"); 
}).listen(8444);

console.log('Server listening:', `http://localhost:${server.address().port}`);
```

to run the server, just do 

```sh
node ./backend.js
```

now you can try your server with

```sh
curl --cacert ./ca/ca-backend.cer 'https://api.backend.oto.tools:8444/'
```

This should output :
```json
{ "message": "Hello World!" }
```

now modify your backend server to ensure that the client provides a client certificate like:

```js
const fs = require('fs'); 
const https = require('https'); 

const options = { 
  key: fs.readFileSync('./server/_.backend.oto.tools.key'), 
  cert: fs.readFileSync('./server/_.backend.oto.tools.cer'), 
  ca: fs.readFileSync('./ca/ca-backend.cer'), 
  requestCert: true, 
  rejectUnauthorized: true
}; 

const server = https.createServer(options, (req, res) => { 
  console.log('Client certificate CN: ', req.socket.getPeerCertificate().subject.CN);
  res.writeHead(200, {
    'Content-Type': 'application/json'
  }); 
  res.end(JSON.stringify({ message: 'Hello World!' }) + "\n"); 
}).listen(8444);

console.log('Server listening:', `http://localhost:${server.address().port}`);
```

you can test your new server with

```sh
curl \
  --cacert ./ca/ca-backend.cer \
  --cert ./client/_.backend.oto.tools.pem \
  --key ./client/_.backend.oto.tools.key 'https://api.backend.oto.tools:8444/'
```

the output should be :

```json
{ "message": "Hello World!" }
```

### Otoroshi setup

Download the latest version of the Otoroshi jar and run it like

```sh
 java \
  -Dapp.adminPassword=password \
  -Dotoroshi.ssl.fromOutside.clientAuth=Want \
  -jar -Dapp.storage=file otoroshi.jar

[info] otoroshi-env - Admin API exposed on http://otoroshi-api.oto.tools:8080
[info] otoroshi-env - Admin UI  exposed on http://otoroshi.oto.tools:8080
[info] otoroshi-in-memory-datastores - Now using InMemory DataStores
[info] otoroshi-env - The main datastore seems to be empty, registering some basic services
[info] otoroshi-env - You can log into the Otoroshi admin console with the following credentials: admin@otoroshi.io / password
[info] play.api.Play - Application started (Prod)
[info] p.c.s.AkkaHttpServer - Listening for HTTP on /0:0:0:0:0:0:0:0:8080
[info] p.c.s.AkkaHttpServer - Listening for HTTPS on /0:0:0:0:0:0:0:0:8443
[info] otoroshi-env - Generating a self signed SSL certificate for https://*.oto.tools ...
```

and log into otoroshi with the tuple `admin@otoroshi.io / password` displayed in the logs. 

Once logged in, navigate to the services pages and create a new item.

* Jump to the `Service exposition settings` and add `http://api.frontend.oto.tools` as `Exposed domain`. 
* Navigate to the `Service targets` and add the following url `https://api.backend.oto.tools:8444/` to redirect our call to the previous created backend. 
* End this step by exposing the service as `Public UI` on the `URL Patterns` section.

and test it

```sh
curl 'http://api.frontend.oto.tools:8080/'
```

This should output :
```json
{"Otoroshi-Error": "Something went wrong, you should try later. Thanks for your understanding."}
```

you should get an error due to the fact that Otoroshi doesn't know about the server certificate or the client certificate expected by the server.

We have to add the client certificate for `https://api.backend.oto.tools` to Otoroshi. 

Go to http://otoroshi.oto.tools:8080/bo/dashboard/certificates and create a new item. Copy and paste the content of `./client/_.backend.oto.tools.cer` and `./client/_.backend.oto.tools.key` respectively in `Certificate full chain` and `Certificate private key`.

If you don't want to bother with UI copy/paste, you can use the import bundle api endpoint to create an otoroshi certificate automatically from a PEM bundle.

```sh
cat ./server/_.backend.oto.tools.cer ./ca/ca-backend.cer ./server/_.backend.oto.tools.key | curl \
  -H 'Content-Type: text/plain' -X POST \
  --data-binary @- \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  http://otoroshi-api.oto.tools:8080/api/certificates/_bundle 
```

and retry the following curl command 

```sh
curl 'http://api.frontend.oto.tools:8080/'
```
the output should be

```json
{"message":"Hello World!"}
```

now we have to expose `https://api.frontend.oto.tools:8443` using otoroshi. 

Go to http://otoroshi.oto.tools:8080/bo/dashboard/certificates and create a new item. Copy and paste the content of `./server/_.frontend.oto.tools.cer` and `./server/_.frontend.oto.tools.key` respectively in `Certificate full chain` and `Certificate private key`.

If you don't want to bother with UI copy/paste, you can use the import bundle api endpoint to create an otoroshi certificate automatically from a PEM bundle.

```sh
cat ./server/_.frontend.oto.tools.cer ./ca/ca-frontend.cer ./server/_.frontend.oto.tools.key | curl \
  -H 'Content-Type: text/plain' -X POST \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  --data-binary @- \
  http://otoroshi-api.oto.tools:8080/api/certificates/_bundle
```

and try the following command

```sh
curl --cacert ./ca/ca-frontend.cer 'https://api.frontend.oto.tools:8443/'
```
the output should be

```json
{"message":"Hello World!"}
```

now we have to enforce the fact that we want client certificate for `api.frontend.oto.tools`. To do that, we have to add a `Client Validator Only` plugin on the `api.frontend.oto.tools` service. Scroll to the last section called `Plugins` and select the `Client validator only` in the list.

now if you retry 

```sh
curl --cacert ./ca/ca-frontend.cer 'https://api.frontend.oto.tools:8443/'
```
the output should be

```json
{"Otoroshi-Error":"bad request"}
```

you should get an error because no client cert. is passed with the request. But if you pass the `./client/_.frontend.oto.tools.csr` client cert and the key in your curl call

```sh
curl 'https://api.frontend.oto.tools:8443' \
  --cacert ./ca/ca-frontend.cer \
  --cert ./client/_.frontend.oto.tools.pem \
  --key ./client/_.frontend.oto.tools.key
```
the output should be

```json
{"message":"Hello World!"}
```

### Client certificate matching plugin

Otoroshi can restrict and check all incoming client certificates on a service.

Scroll to the `Plugins` section and select the `Client certificate matching` plugin. Then, click on the `show config. panel` and inject the default configuration of the plugin (by clicking on `Inject default config.`).

Save the service and retry your call again.

```sh
curl 'https://api.frontend.oto.tools:8443' \
  --cacert ./ca/ca-frontend.cer \
  --cert ./client/_.frontend.oto.tools.pem \
  --key ./client/_.frontend.oto.tools.key
```
the output should be

```json
{"Otoroshi-Error":"bad request"}
```

Our client certificate is not matched by Otoroshi. We have to add the subject DN in the configuration of the `Client certificate matching` plugin to authorize it.

```json
{
  "HasClientCertMatchingValidator": {
    "serialNumbers": [],
    "subjectDNs": [
      "CN=*.frontend.oto.tools"
    ],
    "issuerDNs": [],
    "regexSubjectDNs": [],
    "regexIssuerDNs": []
  }
}
```

Save the service and retry your call again.

```sh
curl 'https://api.frontend.oto.tools:8443' \
  --cacert ./ca/ca-frontend.cer \
  --cert ./client/_.frontend.oto.tools.pem \
  --key ./client/_.frontend.oto.tools.key
```
the output should be

```json
{"message":"Hello World!"}
```


