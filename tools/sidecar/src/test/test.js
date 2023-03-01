
const enableOriginCheck = false;
const rejectUnauthorized = true;
const requestCert = true;
// process.env['NODE_TLS_REJECT_UNAUTHORIZED'] = 0

const fs = require('fs');
const http = require('http');
const https = require('https');
const jwt = require('jsonwebtoken');

const { InternalProxy, ExternalProxy } = require('../proxy');

function Otoroshi(opts) {

  const server = https.createServer({
    key: opts.key,
    cert: opts.cert,
    ca: opts.ca,
    rejectUnauthorized: opts.rejectUnauthorized,
    requestCert: opts.requestCert  
  }, (req, res) => { // from internal proxy
    let data = '';
    const isJson = (req.headers['content-type'] || '').indexOf('application/json') > -1;
    req.on('data', chunk => {
      data = data + chunk;
    });
    data = isJson ? JSON.parse(data) : data
    req.on('end', () => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.write(JSON.stringify({ 
        message: "otoroshi-response",
        method: req.method,
        path: req.url,
        headers: req.headers,
        body: data
      }));
      res.end();
    });
  });

  return {
    listen: (lopts) => {
      return server.listen(lopts.port, lopts.hostname);
    }
  };
}

function Backend(opts) {

  const server = http.createServer((req, res) => {

    const options = {
      hostname: '127.0.0.1', // to internal proxy, caught by iptables
      port: 9901,
      path: '/apis/stuff',
      method: 'GET',
      headers: {
        host: 'api.oto.tools'
      }
    };

    const request = http.request(options, (resp) => {
      const response = {
        message: 'from other api',
        statusCode: resp.statusCode,
        headers: resp.headers,
        body: ''
      }
      const isJson = (resp.headers['content-type'] || '').indexOf('application/json') > -1;
      resp.on('data', (d) => {
        response.body = response.body + d
      });
      resp.on('end', () => {
        response.body = isJson ? JSON.parse(response.body) : response.body
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.write(JSON.stringify(response));
        res.end();
      })
    }).on('error', (e) => {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.write(JSON.stringify({ 
        message: 'error from other api',
        method: req.method,
        path: req.url,
        headers: req.headers,
        error: e
      }));
      res.end();
    });
    request.end();
  });

  return {
    listen: (lopts) => {
      return server.listen(lopts.port, lopts.hostname);
    }
  };
}

function Client(opts) {

  function call() {
    return new Promise((success, failure) => {
      const options = {
        hostname: 'api-test.oto.tools', // to external proxy proxy, caught by iptables
        port: 9902,
        path: '/test',
        method: 'GET',
        headers: {
          host: 'api-test.oto.tools',
          'otoroshi-state': jwt.sign({ state: '1234' }, 'secret'),
          'otoroshi-claim': jwt.sign({ user: 'bobby' }, 'secret'),
        },
        ca: opts.ca,
        key: opts.key,
        cert: opts.cert
      };

      const request = https.request(options, (resp) => {
        const response = {
          message: 'from test api',
          statusCode: resp.statusCode,
          headers: resp.headers,
          body: ''
        }
        const isJson = (resp.headers['content-type'] || '').indexOf('application/json') > -1;
        resp.on('data', (d) => {
          response.body = response.body + d
        });
        resp.on('end', () => {
          response.body = isJson ? JSON.parse(response.body) : response.body
          console.log('response from test');
          console.log(JSON.stringify(response, null, 2));
          success()
        })
      }).on('error', (e) => {
        console.log('client error', e)
        failure();
      });

      console.log('calling external proxy')
      request.end();
    });
  }

  return {
    call: () => call()
  };
}

function context() {
  return {
    OTOROSHI_DOMAIN: 'oto.tools',
    OTOROSHI_HOST:   'otoroshi.oto.tools',
    OTOROSHI_PORT:   9904,
    LOCAL_PORT:      9903,
    CLIENT_ID:       'client-id',
    CLIENT_SECRET:   'client-secret',
    CLIENT_CA:       fs.readFileSync('./certs/ca.cer'),
    CLIENT_CERT:     fs.readFileSync('./certs/oto-client.cer'),
    CLIENT_KEY:      fs.readFileSync('./certs/oto-client.key'),
    BACKEND_CA:      fs.readFileSync('./certs/ca.cer'),
    BACKEND_CERT:    fs.readFileSync('./certs/backend.cer'),
    BACKEND_KEY:     fs.readFileSync('./certs/backend.key'),
    TOKEN_SECRET:    'secret',
  }
}


const internalProxy = InternalProxy({ rejectUnauthorized, requestCert, enableOriginCheck, context }).listen({ hostname: '127.0.0.1', port: 9901 })
const externalProxy = ExternalProxy({ rejectUnauthorized, requestCert, enableOriginCheck, context }).listen({ hostname: '127.0.0.1', port: 9902 })

const backend = Backend().listen({ hostname: '127.0.0.1', port: 9903 })

const otoroshi = Otoroshi({ 
  key:  fs.readFileSync('./certs/oto.key'),
  cert: fs.readFileSync('./certs/oto.cer'),
  ca:   fs.readFileSync('./certs/ca.cer'),
  rejectUnauthorized,
  requestCert
}).listen({ hostname: '127.0.0.1', port: 9904 })

const client = Client({ 
  key:  fs.readFileSync('./certs/client.key'),
  cert: fs.readFileSync('./certs/client.cer'),
  ca:   fs.readFileSync('./certs/ca.cer'),
})

client.call().then(() => {
  internalProxy.close();
  externalProxy.close();
  backend.close();
  otoroshi.close();
}).catch(e => {
  internalProxy.close();
  externalProxy.close();
  backend.close();
  otoroshi.close();
}) 

