const express = require('express');
const bodyParsers = require('body-parser');

const app = express();

app.use(bodyParsers.json());

const mockJwtVerifier = {
  "apiVersion": "proxy.otoroshi.io/v1alpha1",
  "kind": "JwtVerifier",
  "metadata": {
    "creationTimestamp": "2020-11-20T09:55:32Z",
    "generation": 1,
    "name": "jwt-verifier",
    "namespace": "default",
    "resourceVersion": "115398009",
    "uid": "f73679c0-d952-43ab-93bf-f733c9356dad"
  },
  "spec": {
    "algoSettings": {
      "base64": false,
      "secret": "secret",
      "size": 512,
      "type": "HSAlgoSettings"
    },
    "description": "token jwt verifier",
    "source": {
      "name": "Authorization",
      "remove": "Bearer ",
      "type": "InHeader"
    },
    "strategy": {
      "algoSettings": {
        "base64": false,
        "secret": "secret",
        "size": 512,
        "type": "HSAlgoSettings"
      },
      "transformSettings": {
        "location": {
          "name": "Authorization",
          "remove": "Bearer ",
          "type": "InHeader"
        },
        "mappingSettings": {
          "map": {},
          "remove": [],
          "values": {
            "foo-ide": "${token.user:null}",
            "foo-idp": "${token.id:null}",
            "foo-num": "${token.num|token.num:null}",
            "num": "${token.num|token.num:null}",
            "roles": "${token.auth|token.roles:null},${apikey.metadata.ROLES:null}",
            "sub": "${token.user:null}"
          }
        }
      },
      "type": "Transform",
      "verificationSettings": {
        "arrayFields": {},
        "fields": {}
      }
    }
  }
}

function handle(req, res) {
  if (req.query.watch === '1' || req.query.watch === 1) {
    console.log('watch', req.method, req.path, req.query.watch, req.query.resourceVersion, req.query.timeoutSeconds)
    res.writeHead(204, { 'Content-Type': 'application/json' });
    setTimeout(() => {
      res.end();
    }, parseInt(req.query.timeoutSeconds, 10) * 1000)
  } else {
    if (req.params.resource === 'jwt-verifiers') {
      // console.log(`handle request on ${req.method} ${req.path}`)
      res.status(200).send({ items: [mockJwtVerifier] })
    } else {
      res.status(200).send({
        apiVersion: req.params.api || 'v1',
        kind: req.params.resource,
        metadata: {
          name: "http-app-group",
          namespace: req.params.namespace || 'default',
          resourceVersion: "1"
        },       
        spec: {
          description: "a group to hold services about the http-app"
        }
      });
    }
  }
}

function fakeDNS(req, res) {
  res.status(200).send(JSON.parse(`{
    "apiVersion": "operator.openshift.io/v1",
    "kind": "DNS",
    "metadata": {
      "creationTimestamp": "2020-08-18T16:08:43Z",
      "finalizers": [
        "dns.operator.openshift.io/dns-controller"
      ],
      "generation": 11,
      "name": "default",
      "resourceVersion": "46598410",
      "selfLink": "/apis/operator.openshift.io/v1/dnses/default",
      "uid": "32967567-11bc-46d7-8af2-bf5af50e66ee"
    },
    "spec": {
      "servers": [
        {
          "forwardPlugin": {
            "upstreams": [
              "172.30.150.28:5353"
            ]
          },
          "name": "otoroshi-dns",
          "zones": [
            "gateway-api-tdv.otoroshi.mesh"
          ]
        }
      ]
    }
  }`))
}

function fakeService(req, res) {
  res.status(200).send(JSON.parse(`{
    "apiVersion": "v1",
    "kind": "Service",
    "metadata": {
      "name": "otoroshi-dns",
      "namespace": "gateway-api-tdv",
      "labels": {
        "app": "otoroshi",
        "component": "coredns"
      }
    },
    "spec": {
      "clusterIP": "172.30.150.29",
      "selector": {
        "app": "otoroshi",
        "component": "coredns"
      },
      "type": "ClusterIP",
      "ports": [
        {
          "name": "dns",
          "port": 5353,
          "protocol": "UDP"
        },
        {
          "name": "dns-tcp",
          "port": 5353,
          "protocol": "TCP"
        }
      ]
    }
  }`))
}

function fakeDNSPost(req, res) {
  console.log(req.headers);
  console.log(JSON.stringify(req.body, null, 2))
  res.status(200).send({});
}

app.patch('/apis/operator.openshift.io/v1/dnses/default', fakeDNSPost); 
app.get('/apis/operator.openshift.io/v1/dnses/default', fakeDNS); 
app.get('/apis/v1/services/otoroshi-dns', fakeService); 
app.get('/apis/:version/:resource', handle);
app.get('/apis/:api/:version/:resource', handle);

app.all('/*', (req, res) => {
  console.log(`unhandled request on ${req.method} ${req.path}`)
  res.status(204).send('');
})

app.listen(6443, () => {
  console.log(`fake kube listening at http://127.0.0.1:6443`)
});