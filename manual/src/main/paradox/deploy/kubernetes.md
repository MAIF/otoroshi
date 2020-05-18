# Kubernetes Integration

Starting at version 1.5.0, Otoroshi provides a native Kubernetes support. Multiple jobs are provided in order to

- sync kubernetes secrets of type `kubernetes.io/tls` to otoroshi certificates
- act as a standard ingress controller
- provide Custom Resource Definitions (CRDs) in order to manage Otoroshi entities from Kubernetes

## Deploy otoroshi on your kubernetes cluster

If you want to deploy otoroshi into your kubernetes cluster, you can download the deployment descriptors from https://github.com/MAIF/otoroshi/tree/master/kubernetes and use kustomize to create your own overlay. The base descriptors are available at https://github.com/MAIF/otoroshi/tree/master/kubernetes/base

Then deploy it with `kubectl apply -k ./overlays/myoverlay`. 

You can also create a `kustomization.yaml` file with a remote base

```yaml
bases:
- github.com/MAIF/otoroshi/kubernetes/overlays/prod/?ref=v1.5.0
```

and apply it

An Helm chart will be available as soon as possible

## Use Otoroshi as an Ingress Controller

If you want to use Otoroshi as an [Ingress Controller](https://kubernetes.io/fr/docs/concepts/services-networking/ingress/), just go to the danger zone, and in `Global scripts` add the job named `Kubernetes Ingress Controller`.

Then add the following configuration for the job (with your own tweak of course)

```json
{
  "KubernetesConfig": {
    "enabled": true,
    "endpoint": "https://127.0.0.1:6443",
    "token": "eyJhbGciOiJSUzI....F463SrpOehQRaQ",
    "namespaces": [
      "*"
    ]
  }
}
```

the configuration can have the following values 

```javascript
{
  "KubernetesConfig": {
    "endpoint": "https://127.0.0.1:6443", // the endpoint to talk to the kubernetes api, optional
    "token": "xxxx", // the bearer token to talk to the kubernetes api, optional
    "userPassword": "user:password", // the user password tuple to talk to the kubernetes api, optional
    "caCert": "/etc/ca.cert", // the ca cert file path to talk to the kubernetes api, optional
    "trust": false, // trust any cert to talk to the kubernetes api, optional
    "namespaces": ["*"], // the watched namespaces
    "labels": ["label"], // the watched namespaces
    "ingressClass": "otoroshi", // the watched kubernetes.io/ingress.class annotation, can be *
    "defaultGroup": "default", // the group to put services in otoroshi
    "ingresses": true, // sync ingresses
    "crds": false, // sync crds
    "kubeLeader": false, // delegate leader election to kubernetes, to know where the sync job should run
    "restartDependantDeployments": true, // when a secret/cert changes from otoroshi sync, restart dependant deployments
    "templates": {
      "service-group": {},
      "service-descriptor": {},
      "apikeys": {},
      "global-config": {},
      "jwt-verifier": {},
      "tcp-service": {},
      "certificate": {},
      "auth-module": {},
      "script": {},
    }
  }
}
```

If `endpoint` is not defined, Otoroshi will try to get it from `$KUBERNETES_SERVICE_HOST` and `$KUBERNETES_SERVICE_PORT`.
If `token` is not defined, Otoroshi will try to get it from the file at `/var/run/secrets/kubernetes.io/serviceaccount/token`.
If `caCert` is not defined, Otoroshi will try to get it from the file at `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`.
If `$KUBECONFIG` is defined, `endpoint`, `token` and `caCert` will be read from the current context of the file referenced by it.

Now you can deploy your first service ;)

### Deploy an ingress route

now let's say you want to deploy an http service and route to the outside world through otoroshi

```yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: http-app-deployment
spec:
  selector:
    matchLabels:
      run: http-app-deployment
  replicas: 1
  template:
    metadata:
      labels:
        run: http-app-deployment
    spec:
      containers:
      - image: kennethreitz/httpbin
        imagePullPolicy: IfNotPresent
        name: otoroshi
        ports:
          - containerPort: 80
            name: "http"
---
apiVersion: v1
kind: Service
metadata:
  name: http-app-service
spec:
  ports:
    - port: 8080
      targetPort: http
      name: http
  selector:
    run: http-app-deployment
---
apiVersion: networking.k8s.io/v1beta1
kind: Ingress
metadata:
  name: http-app-ingress
  annotations:
    kubernetes.io/ingress.class: otoroshi
spec:
  tls:
  - hosts:
    - httpapp.foo.bar
    secretName: http-app-cert
  rules:
  - host: httpapp.foo.bar
    http:
      paths:
      - path: /
        backend:
          serviceName: http-app-service
          servicePort: 8080
```

once deployed, otoroshi will sync with kubernetes and create the corresponding service to route your app. You will be able to access your app with

```sh
curl -X GET https://httpapp.foo.bar/get
```

### Use multiple ingress controllers

It is of course possible to use multiple ingress controller at the same time (https://kubernetes.io/docs/concepts/services-networking/ingress-controllers/#using-multiple-ingress-controllers) using the annotation `kubernetes.io/ingress.class`. By default, otoroshi reacts to the class `otoroshi`, but you can make it the default ingress controller with the following config

```json
{
  "KubernetesConfig": {
    ...
    "ingressClass": "*",
    ...
  }
}
```

### Supported annotations

if you need to customize the service descriptor behind an ingress rule, you can use some annotations. If you need better customisation, just go to the CRDs part. The following annotations are supported :

- `otoroshi.ingress.kubernetes.io/group`
- `otoroshi.ingress.kubernetes.io/groupId`
- `otoroshi.ingress.kubernetes.io/name`
- `otoroshi.ingress.kubernetes.io/targetsLoadBalancing`
- `otoroshi.ingress.kubernetes.io/stripPath`
- `otoroshi.ingress.kubernetes.io/enabled`
- `otoroshi.ingress.kubernetes.io/userFacing`
- `otoroshi.ingress.kubernetes.io/privateApp`
- `otoroshi.ingress.kubernetes.io/forceHttps`
- `otoroshi.ingress.kubernetes.io/maintenanceMode`
- `otoroshi.ingress.kubernetes.io/buildMode`
- `otoroshi.ingress.kubernetes.io/strictlyPrivate`
- `otoroshi.ingress.kubernetes.io/sendOtoroshiHeadersBack`
- `otoroshi.ingress.kubernetes.io/readOnly`
- `otoroshi.ingress.kubernetes.io/xForwardedHeaders`
- `otoroshi.ingress.kubernetes.io/overrideHost`
- `otoroshi.ingress.kubernetes.io/allowHttp10`
- `otoroshi.ingress.kubernetes.io/logAnalyticsOnServer`
- `otoroshi.ingress.kubernetes.io/useAkkaHttpClient`
- `otoroshi.ingress.kubernetes.io/useNewWSClient`
- `otoroshi.ingress.kubernetes.io/tcpUdpTunneling`
- `otoroshi.ingress.kubernetes.io/detectApiKeySooner`
- `otoroshi.ingress.kubernetes.io/letsEncrypt`
- `otoroshi.ingress.kubernetes.io/publicPatterns`
- `otoroshi.ingress.kubernetes.io/privatePatterns`
- `otoroshi.ingress.kubernetes.io/additionalHeaders`
- `otoroshi.ingress.kubernetes.io/additionalHeadersOut`
- `otoroshi.ingress.kubernetes.io/missingOnlyHeadersIn`
- `otoroshi.ingress.kubernetes.io/missingOnlyHeadersOut`
- `otoroshi.ingress.kubernetes.io/removeHeadersIn`
- `otoroshi.ingress.kubernetes.io/removeHeadersOut`
- `otoroshi.ingress.kubernetes.io/headersVerification`
- `otoroshi.ingress.kubernetes.io/matchingHeaders`
- `otoroshi.ingress.kubernetes.io/ipFiltering.whitelist`
- `otoroshi.ingress.kubernetes.io/ipFiltering.blacklist`
- `otoroshi.ingress.kubernetes.io/api.exposeApi`
- `otoroshi.ingress.kubernetes.io/api.openApiDescriptorUrl`
- `otoroshi.ingress.kubernetes.io/healthCheck.enabled`
- `otoroshi.ingress.kubernetes.io/healthCheck.url`
- `otoroshi.ingress.kubernetes.io/jwtVerifier.ids`
- `otoroshi.ingress.kubernetes.io/jwtVerifier.enabled`
- `otoroshi.ingress.kubernetes.io/jwtVerifier.excludedPatterns`
- `otoroshi.ingress.kubernetes.io/authConfigRef`
- `otoroshi.ingress.kubernetes.io/redirection.enabled`
- `otoroshi.ingress.kubernetes.io/redirection.code`
- `otoroshi.ingress.kubernetes.io/redirection.to`
- `otoroshi.ingress.kubernetes.io/clientValidatorRef`
- `otoroshi.ingress.kubernetes.io/transformerRefs`
- `otoroshi.ingress.kubernetes.io/transformerConfig`
- `otoroshi.ingress.kubernetes.io/accessValidator.enabled`
- `otoroshi.ingress.kubernetes.io/accessValidator.excludedPatterns`
- `otoroshi.ingress.kubernetes.io/accessValidator.refs`
- `otoroshi.ingress.kubernetes.io/accessValidator.config`
- `otoroshi.ingress.kubernetes.io/preRouting.enabled`
- `otoroshi.ingress.kubernetes.io/preRouting.excludedPatterns`
- `otoroshi.ingress.kubernetes.io/preRouting.refs`
- `otoroshi.ingress.kubernetes.io/preRouting.config`
- `otoroshi.ingress.kubernetes.io/issueCert`
- `otoroshi.ingress.kubernetes.io/issueCertCA`
- `otoroshi.ingress.kubernetes.io/gzip.enabled`
- `otoroshi.ingress.kubernetes.io/gzip.excludedPatterns`
- `otoroshi.ingress.kubernetes.io/gzip.whiteList`
- `otoroshi.ingress.kubernetes.io/gzip.blackList`
- `otoroshi.ingress.kubernetes.io/gzip.bufferSize`
- `otoroshi.ingress.kubernetes.io/gzip.chunkedThreshold`
- `otoroshi.ingress.kubernetes.io/gzip.compressionLevel`
- `otoroshi.ingress.kubernetes.io/cors.enabled`
- `otoroshi.ingress.kubernetes.io/cors.allowOrigin`
- `otoroshi.ingress.kubernetes.io/cors.exposeHeaders`
- `otoroshi.ingress.kubernetes.io/cors.allowHeaders`
- `otoroshi.ingress.kubernetes.io/cors.allowMethods`
- `otoroshi.ingress.kubernetes.io/cors.excludedPatterns`
- `otoroshi.ingress.kubernetes.io/cors.maxAge`
- `otoroshi.ingress.kubernetes.io/cors.allowCredentials`
- `otoroshi.ingress.kubernetes.io/clientConfig.useCircuitBreaker`
- `otoroshi.ingress.kubernetes.io/clientConfig.retries`
- `otoroshi.ingress.kubernetes.io/clientConfig.maxErrors`
- `otoroshi.ingress.kubernetes.io/clientConfig.retryInitialDelay`
- `otoroshi.ingress.kubernetes.io/clientConfig.backoffFactor`
- `otoroshi.ingress.kubernetes.io/clientConfig.connectionTimeout`
- `otoroshi.ingress.kubernetes.io/clientConfig.idleTimeout`
- `otoroshi.ingress.kubernetes.io/clientConfig.callAndStreamTimeout`
- `otoroshi.ingress.kubernetes.io/clientConfig.callTimeout`
- `otoroshi.ingress.kubernetes.io/clientConfig.globalTimeout`
- `otoroshi.ingress.kubernetes.io/clientConfig.sampleInterval`
- `otoroshi.ingress.kubernetes.io/enforceSecureCommunication`
- `otoroshi.ingress.kubernetes.io/sendInfoToken`
- `otoroshi.ingress.kubernetes.io/sendStateChallenge`
- `otoroshi.ingress.kubernetes.io/secComHeaders.claimRequestName`
- `otoroshi.ingress.kubernetes.io/secComHeaders.stateRequestName`
- `otoroshi.ingress.kubernetes.io/secComHeaders.stateResponseName`
- `otoroshi.ingress.kubernetes.io/secComTtl`
- `otoroshi.ingress.kubernetes.io/secComVersion`
- `otoroshi.ingress.kubernetes.io/secComInfoTokenVersion`
- `otoroshi.ingress.kubernetes.io/secComExcludedPatterns`
- `otoroshi.ingress.kubernetes.io/secComSettings.size`
- `otoroshi.ingress.kubernetes.io/secComSettings.secret`
- `otoroshi.ingress.kubernetes.io/secComSettings.base64`
- `otoroshi.ingress.kubernetes.io/secComUseSameAlgo`
- `otoroshi.ingress.kubernetes.io/secComAlgoChallengeOtoToBack.size`
- `otoroshi.ingress.kubernetes.io/secComAlgoChallengeOtoToBack.secret`
- `otoroshi.ingress.kubernetes.io/secComAlgoChallengeOtoToBack.base64`
- `otoroshi.ingress.kubernetes.io/secComAlgoChallengeBackToOto.size`
- `otoroshi.ingress.kubernetes.io/secComAlgoChallengeBackToOto.secret`
- `otoroshi.ingress.kubernetes.io/secComAlgoChallengeBackToOto.base64`
- `otoroshi.ingress.kubernetes.io/secComAlgoInfoToken.size`
- `otoroshi.ingress.kubernetes.io/secComAlgoInfoToken.secret`
- `otoroshi.ingress.kubernetes.io/secComAlgoInfoToken.base64`
- `otoroshi.ingress.kubernetes.io/securityExcludedPatterns`

for more informations about it, just go to https://maif.github.io/otoroshi/swagger-ui/index.html

with the previous example, the ingress does not define any apikey, so the route is public. If you want to enable apikeys on it, you can deploy the following descriptor

```yaml
apiVersion: networking.k8s.io/v1beta1
kind: Ingress
metadata:
  name: http-app-ingress
  annotations:
    kubernetes.io/ingress.class: otoroshi
    otoroshi.ingress.kubernetes.io/group: http-app-group
    otoroshi.ingress.kubernetes.io/forceHttps: 'true'
    otoroshi.ingress.kubernetes.io/sendOtoroshiHeadersBack: 'true'
    otoroshi.ingress.kubernetes.io/overrideHost: 'true'
    otoroshi.ingress.kubernetes.io/allowHttp10: 'false'
    otoroshi.ingress.kubernetes.io/publicPatterns: ''
spec:
  tls:
  - hosts:
    - httpapp.foo.bar
    secretName: http-app-cert
  rules:
  - host: httpapp.foo.bar
    http:
      paths:
      - path: /
        backend:
          serviceName: http-app-service
          servicePort: 8080
```

now you can use an existing apikey in the `http-app-group` to access your app

```sh
curl -X GET https://httpapp.foo.bar/get -u existing-apikey-1:secret-1
```

## Use Otoroshi CRDs for a better/full integration

Otoroshi provides some Custom Resource Definitions for kubernetes in order to manager Otoroshi related entities in kubernetes

- `service-groups`
- `service-descriptors`
- `apikeys`
- `certificates`
- `global-configs`
- `jwt-verifiers`
- `auth-modules`
- `scripts`
- `tcp-services`
- `admins`

using CRDs, you will be able to deploy and manager those entities from kubectl or the kubernetes api like

```sh
sudo kubectl get apikeys --all-namespaces
sudo kubectl get service-descriptors --all-namespaces
curl -X GET \
  -H 'Authorization: Bearer eyJhbGciOiJSUzI....F463SrpOehQRaQ' \
  -H 'Accept: application/json' -k \
  https://127.0.0.1:6443/apis/proxy.otoroshi.io/v1alpha1/apikeys | jq
```

@@@ warning
when using Otoroshi CRDs, Kubernetes becomes the single source of truth for the synced entities. It means that any value in the descriptors deployed will overrides the one in Otoroshi datastore each time it's synced. So be careful if you use the Otoroshi UI or the API, some changes in configuration may be overriden by CRDs sync job.
@@@

To configure it, just go to the danger zone, and in `Global scripts` add the job named `Kubernetes Otoroshi CRDs Controller`. Then add the following configuration for the job (with your own tweak of course)

```json
{
  "KubernetesConfig": {
    "enabled": true,
    "crds": true,
    "endpoint": "https://127.0.0.1:6443",
    "token": "eyJhbGciOiJSUzI....F463SrpOehQRaQ",
    "namespaces": [
      "*"
    ]
  }
}
```

then you can deploy the previous example with better configuration level, and using mtls, apikeys, etc

Let say the app looks like :

```js
const fs = require('fs'); 
const https = require('https'); 

// here we read the apikey to access http-app-2 from files mounted from secrets
const clientId = fs.readFileSync('/var/run/secrets/kubernetes.io/apikeys/clientId').toString('utf8')
const clientSecret = fs.readFileSync('/var/run/secrets/kubernetes.io/apikeys/clientSecret').toString('utf8')

// here we read the certificate for the app
const crt = fs.readFileSync('/var/run/secrets/kubernetes.io/certs/tls.crt')
  .toString('utf8')
  .split('-----BEGIN CERTIFICATE-----\n')
  .filter(s => s.trim() !== '');
const cert = '-----BEGIN CERTIFICATE-----\n' + crt.shift()
const ca = crt.join('-----BEGIN CERTIFICATE-----\n')

function callApi2() {
  return new Promise((success, failure) => {
    const options = { 
      hostname: 'httpapp2.foo.bar', 
      port: 433, 
      path: '/', 
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'Otoroshi-Client-Id': clientId,
        'Otoroshi-Client-Secret': clientSecret,
      }
    }; 
    let data = '';
    const req = https.request(options, (res) => { 
      res.on('data', (d) => { 
        data = data + d.toString('utf8');
      }); 
      res.on('end', () => { 
        success({ body: JSON.parse(data), res });
      }); 
      res.on('error', (e) => { 
        failure(e);
      }); 
    }); 
    req.end();
  })
}

const options = { 
  key: fs.readFileSync('/var/run/secrets/kubernetes.io/certs/tls.key'), 
  cert: cert, 
  ca: ca, 
  // we want mtls behavior
  requestCert: true, 
  rejectUnauthorized: true
}; 
https.createServer(options, (req, res) => { 
  res.writeHead(200, {'Content-Type': 'application/json'});
  callApi2().then(resp => {
    res.write(JSON.stringify{ ("message": `Hello to ${req.socket.getPeerCertificate().subject.CN}`, api2: resp.body })); 
  });
}).listen(433);
```

then, the descriptors will be :

```yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: http-app-deployment
spec:
  selector:
    matchLabels:
      run: http-app-deployment
  replicas: 1
  template:
    metadata:
      labels:
        run: http-app-deployment
    spec:
      containers:
      - image: foo/http-app
        imagePullPolicy: IfNotPresent
        name: otoroshi
        ports:
          - containerPort: 443
            name: "https"
        volumeMounts:
        - name: apikey-volume
          # here you will be able to read apikey from files 
          # - /var/run/secrets/kubernetes.io/apikeys/clientId
          # - /var/run/secrets/kubernetes.io/apikeys/clientSecret
          mountPath: "/var/run/secrets/kubernetes.io/apikeys"
          readOnly: true
        volumeMounts:
        - name: cert-volume
          # here you will be able to read app cert from files 
          # - /var/run/secrets/kubernetes.io/certs/tls.crt
          # - /var/run/secrets/kubernetes.io/certs/tls.key
          mountPath: "/var/run/secrets/kubernetes.io/certs"
          readOnly: true
      volumes:
      - name: apikey-volume
        secret:
          # here we reference the secret name from apikey http-app-2-apikey-1
          secretName: secret-2
      - name: cert-volume
        secret:
          # here we reference the secret name from cert http-app-certificate-backend
          secretName: http-app-certificate-backend-secret
---
apiVersion: v1
kind: Service
metadata:
  name: http-app-service
spec:
  ports:
    - port: 8443
      targetPort: httpss
      name: https
  selector:
    run: http-app-deployment
---
apiVersion: proxy.otoroshi.io/v1alpha1
kind: ServiceGroup
metadata:
  name: http-app-group
spec:
  description: a group to hold services about the http-app
---
apiVersion: proxy.otoroshi.io/v1alpha1
kind: ApiKey
metadata:
  name: http-app-apikey-1
# this apikey can be used to access the app
spec:
  # a secret name secret-1 will be created by otoroshi and can be used by containers
  exportSecret: true 
  secretName: secret-1
  group: http-app-group
---
apiVersion: proxy.otoroshi.io/v1alpha1
kind: ApiKey
metadata:
  name: http-app-2-apikey-1
# this apikey can be used to access another app in a different group
spec:
  # a secret name secret-1 will be created by otoroshi and can be used by containers
  exportSecret: true 
  secretName: secret-2
  group: http-app-2-group
---
apiVersion: proxy.otoroshi.io/v1alpha1
kind: Certificate
metadata:
  name: http-app-certificate-frontend
spec:
  description: certificate for the http-app on otorshi frontend
  autoRenew: true
  csr:
    issuer: O=EvilCorp, L=San Francisco, ST=California, C=US
    hosts: 
    - httpapp.foo.bar
    key:
      algo: rsa
      size: 2048
    subject: OU=httpapp-front, O=EvilCorp, L=San Francisco, ST=California, C=US
    client: false
    ca: false
    duration: 31536000000
    signatureAlg: SHA256WithRSAEncryption
    digestAlg: SHA-256
---
apiVersion: proxy.otoroshi.io/v1alpha1
kind: Certificate
metadata:
  name: http-app-certificate-backend
spec:
  description: certificate for the http-app deployed on pods
  autoRenew: true
  # a secret name http-app-certificate-backend-secret will be created by otoroshi and can be used by containers
  exportSecret: true 
  secretName: http-app-certificate-backend-secret
  csr:
    issuer: O=EvilCorp, L=San Francisco, ST=California, C=US
    hosts: 
    - httpapp.foo.bar
    key:
      algo: rsa
      size: 2048
    subject: OU=httpapp-back, O=EvilCorp, L=San Francisco, ST=California, C=US
    client: false
    ca: false
    duration: 31536000000
    signatureAlg: SHA256WithRSAEncryption
    digestAlg: SHA-256
---
apiVersion: proxy.otoroshi.io/v1alpha1
kind: Certificate
metadata:
  name: http-app-certificate-client
spec:
  description: certificate for the http-app
  autoRenew: true
  csr:
    issuer: O=EvilCorp, L=San Francisco, ST=California, C=US
    hosts: 
    - httpapp.foo.bar
    key:
      algo: rsa
      size: 2048
    subject: OU=httpapp-client, O=EvilCorp, L=San Francisco, ST=California, C=US
    client: false
    ca: false
    duration: 31536000000
    signatureAlg: SHA256WithRSAEncryption
    digestAlg: SHA-256
---
apiVersion: proxy.otoroshi.io/v1alpha1
kind: ServiceDescriptor
metadata:
  name: http-app-service-descriptor
spec:
  description: the service descriptor for the http app
  group: http-app-group
  forceHttps: true
  hosts:
  - httpapp.foo.bar
  matchingRoot: /
  targets:
  - url: https://http-app-service:8443
    mtlsConfig:
      # use mtls to contact the backend
      mtls: true
      certs: 
        # reference the DN for the client cert
        - OU=httpapp-client, O=EvilCorp, L=San Francisco, ST=California, C=US
      trustedCerts: 
        # reference the DN for the CA cert
        - O=EvilCorp, L=San Francisco, ST=California, C=US
  sendOtoroshiHeadersBack: true
  xForwardedHeaders: true
  overrideHost: true
  allowHttp10: false
  publicPatterns:
    - /health
  additionalHeaders:
    x-foo: bar
# here you can specify everything supported by otoroshi like jwt-verifiers, auth config, etc ... for more informations about it, just go to https://maif.github.io/otoroshi/swagger-ui/index.html
```

now with this descriptor deployed, you can access your app with a command like 

```sh
CLIENT_ID=`kubectl get secret secret-1 -o jsonpath="{.data.clientId}" | base64 --decode`
CLIENT_SECRET=`kubectl get secret secret-1 -o jsonpath="{.data.clientSecret}" | base64 --decode`
curl -X GET https://httpapp.foo.bar/get -u "$CLIENT_ID:$CLIENT_SECRET"
```

## Expose Otoroshi to outside world

If you deploy Otoroshi on a kubernetes cluster, the Otoroshi service is deployed as a loadbalancer. You'll need to declare in your DNS settings any name that can be routed by otoroshi going to the loadbalancer endpoint of your kubernetes distribution.

## Access a service from inside the k8s cluster

You can access any service referenced in otoroshi, through otoroshin from inside the kubernetes cluster by using the internal otoroshi service (if you use a template based on https://github.com/MAIF/otoroshi/tree/master/kubernetes/base) name and the host header with the service domain like :

```sh
CLIENT_ID="xxx"
CLIENT_SECRET="xxx"
curl -X GET -H 'Host: httpapp.foo.bar' https://otoroshi-internal-service:8443/get -u "$CLIENT_ID:$CLIENT_SECRET"
```