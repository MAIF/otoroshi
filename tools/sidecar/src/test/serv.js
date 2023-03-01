const fs = require('fs');
const http = require('http');

const { InternalProxy, ExternalProxy } = require('../proxy');


function Backend(opts) {

  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write(JSON.stringify({ 'msg': 'hello ' + Date.now() }));
    res.end();
  });

  return {
    listen: (lopts) => {
      return server.listen(lopts.port, lopts.hostname);
    }
  };
}

function context() {
  const a = {
    OTOROSHI_DOMAIN: 'oto.tools',
    OTOROSHI_HOST:   'otoroshi.oto.tools',
    OTOROSHI_PORT:   9904,
    LOCAL_PORT:      9903,
    CLIENT_ID:       'client-id',
    CLIENT_SECRET:   'client-secret',
    CLIENT_CA:       fs.readFileSync('./certs/ca.cer').toString('utf8'),
    CLIENT_CERT:     fs.readFileSync('./certs/client.cer').toString('utf8'),
    CLIENT_KEY:      fs.readFileSync('./certs/client.key').toString('utf8'),
    BACKEND_CA:      fs.readFileSync('./certs/ca.cer').toString('utf8'),
    BACKEND_CERT:    fs.readFileSync('./certs/backend.cer').toString('utf8'),
    BACKEND_KEY:     fs.readFileSync('./certs/backend.key').toString('utf8'),
    TOKEN_SECRET:    'secret',

    "EXTERNAL_PORT": 8443,
    "INTERNAL_PORT": 8080,
    "REQUEST_CERT": false,
    "DISABLE_TOKENS_CHECK": true,
    "REJECT_UNAUTHORIZED": false,
    "ENABLE_ORIGIN_CHECK": false,
    "DISPLAY_ENV": true,
    "ENABLE_TRACE": true
  }
  if (a.DISPLAY_ENV) {
    console.log(JSON.stringify(a, null, 2))
  }
  return a;
}

const ctx = context();

const internalProxy = InternalProxy({ 
  enableTrace: ctx.ENABLE_TRACE, 
  disableTokensCheck: ctx.DISABLE_TOKENS_CHECK, 
  rejectUnauthorized: ctx.REJECT_UNAUTHORIZED, 
  requestCert: ctx.REQUEST_CERT, 
  enableOriginCheck: ctx.ENABLE_ORIGIN_CHECK, 
  context   
}).listen({ hostname: '127.0.0.1', port: ctx.INTERNAL_PORT })
const externalProxy = ExternalProxy({ 
  enableTrace: ctx.ENABLE_TRACE, 
  disableTokensCheck: ctx.DISABLE_TOKENS_CHECK, 
  rejectUnauthorized: ctx.REJECT_UNAUTHORIZED, 
  requestCert: ctx.REQUEST_CERT, 
  enableOriginCheck: ctx.ENABLE_ORIGIN_CHECK, 
  context 
}).listen({ hostname: '0.0.0.0', port: ctx.EXTERNAL_PORT })

const backend = Backend().listen({ hostname: '127.0.0.1', port: 9903 })
