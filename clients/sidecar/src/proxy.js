const http = require('http');
const https = require('https');
const moment = require('moment');
const fs = require('fs');
const uuidv4 = require('uuid/v4');

const OTOROSHI_HOST = process.env.OTOROSHI_HOST || 'otoroshi-service.otoroshi.svc.cluster.local';
const OTOROSHI_PORT = parseInt(process.env.OTOROSHI_PORT || '8443', 10);
const LOCAL_PORT = parseInt(process.env.LOCAL_PORT || '8081', 10);

// TODO: update at interval
const CLIENT_ID = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/apikeys/clientId').toString('utf8')
const CLIENT_SECRET = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/apikeys/clientSecret').toString('utf8')

const CLIENT_CA = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/certs/client/ca-chain.crt').toString('utf8')
const CLIENT_CERT = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/certs/client/cert.crt').toString('utf8')
const CLIENT_KEY = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/certs/client/tls.key').toString('utf8')

const BACKEND_CA = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/certs/backend/ca-chain.crt').toString('utf8')
const BACKEND_CERT = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/certs/backend/cert.crt').toString('utf8')
const BACKEND_KEY = fs.readFileSync('/var/run/secrets/kubernetes.io/otoroshi.io/certs/backend/tls.key').toString('utf8')
// TODO: update at interval

function createServer(opts, fn) {
  if (opts.ssl) {
    const s1 = https.createServer(opts.ssl, (req, res) => {
      return fn(req, res, true)
    });
    return {
      listen: (lopts) => {
        console.log(`[${opts.type}] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} - Proxy listening on https://${lopts.hostname}:${lopts.httpsPort}`);
        s1.listen(lopts.port, lopts.hostname);
      },
      close: () => s1.close()
    };
  } else {
    const s1 = http.createServer((req, res) => {
      return fn(req, res, false)
    });
    return {
      listen: (lopts) => {
        console.log(`[${opts.type}] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} - Proxy listening on http://${lopts.hostname}:${lopts.httpPort}`);
        s1.listen(lopts.port, lopts.hostname);
      },
      close: () => s1.close()
    };
  }
}

function writeError(code, err, ctype, res, args) {
  const _headers = { 'Content-Type': ctype };
  res.writeHead(code, headers);
  res.write(err);
  res.end();
}

function InternalProxy(opts) {

  const server = createServer(opts, (req, res, secure) => {

    try {
      const args = {};

      if (req.socket.localAddress !== '127.0.0.1' && req.socket.localAddress !== 'localhost') {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.write(JSON.stringify({ error: 'bad origin' }));
        res.end();
        return;
      }

      const domain = req.headers.host.split(':')[0];
      const isHandledByOtoroshi = domain.indexOf('otoroshi.mesh') > -1;
      
      const requestId = uuidv4();
      let options = {
        host: OTOROSHI_HOST,
        port: OTOROSHI_PORT,
        path: req.url,
        method: req.method,
        headers: {
          ...req.headers,
          'Otoroshi-Client-Id': CLIENT_ID,
          'Otoroshi-Client-Secret': CLIENT_SECRET,
        },
        cert: CLIENT_CERT,
        key: CLIENT_KEY,
        ca: CLIENT_CA
      };
      req.setEncoding('utf8');
      if (!isHandledByOtoroshi) {
        // TODO: just proxy here and not fail!
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.write(JSON.stringify({ error: 'not an otoroshi request' }));
        res.end();
        return;
      }

      console.log(`[INTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} - ${requestId} - HTTP/${req.httpVersion} - ${req.method} - ${secure ? 'https' : 'http'}://${domain}${req.url}`);

      const forwardReq = https.request(options, (forwardRes) => {
        const headersOut = { ...forwardRes.headers };      
        res.writeHead(forwardRes.statusCode, headersOut);
        forwardRes.setEncoding('utf8');
        let ended = false;
        forwardRes.on('data', (chunk) => res.write(chunk));
        forwardRes.on('close', () => {
          res.end();
          if (!ended) {
            ended = true;
            span.end();
          }
        });
        forwardRes.on('end', () => {
          res.end();
          if (!ended) {
            ended = true;
            span.end();
          }
        });
      }).on('error', (err) => {
        console.log(`[INTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Error while forwarding request`, err);
        if (err.type === 'ProxyError') {
          try {
            writeError(err.status, err.body, err.ctype, res, args);
          } catch (err2) {
            console.log(`[INTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Could not send error response: `, err2, {});
          }
        } else {
          writeError(502, `{"error": "Internal error", "type":"INTERNAL-PROXY", "message":"${err.message}" }`, 'application/json', res, args);
        }
      });
      const contentLength = parseInt(req.headers['Content-Length'] || req.headers['content-length'] || '0', 10);
      const hasContentLength = contentLength > 0;
      const isChunked = (req.headers['Transfer-Encoding'] || req.headers['transfer-encoding'] || 'none') === 'chunked';
      if (hasContentLength || isChunked) {
        req.on('data', (chunk) => forwardReq.write(chunk));
        req.on('close', () => forwardReq.end());
        req.on('end', () => forwardReq.end());
      } else {
        forwardReq.end();
      }
    } catch (_error) {
      console.log(`[INTERNAL_PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} http request to service raised: `, _error, {});
      if (_error.type === 'ProxyError') {
        try {
          writeError(_error.status, _error.body, _error.ctype, res, args);
        } catch (_error2) {
          console.log(`[INTERNAL_PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Could not send error response: `, _error2, {});
        }
      } else {
        try {
          writeError(502, `{"error": "Internal error", "type":"INTERNAL_PROXY", "message":"${_error.message}" }`, 'application/json', res, args);
        } catch (_error2) {
          console.log(`[INTERNAL_PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Could not send error response: `, _error2, {});
        }
      }
    }
  });

  return server;
}

function ExternalProxy(opts) {

  const server = createServer({ ...opts, ssl: {
    key: BACKEND_KEY, 
    cert: BACKEND_CERT, 
    ca: BACKEND_CA, 
    requestCert: true, 
    rejectUnauthorized: true
  } }, (req, res, secure) => {

    try {
      const args = {};

      if (req.socket.localAddress === '127.0.0.1' || req.socket.localAddress === 'localhost') {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.write(JSON.stringify({ error: 'bad origin' }));
        res.end();
        return;
      }
    
      const requestId = uuidv4();
      const stateToken = req.headers['otoroshi-state'];
      const claimToken = req.headers['otoroshi-claim'];
      // TODO: validate tokens
      // TODO: check if client cert
      const options = {
        host: '127.0.0.1',
        port: LOCAL_PORT,
        path: req.url,
        method: req.method,
        headers: {
          ...req.headers
        }
      };
      req.setEncoding('utf8');

      console.log(`[EXTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} - ${requestId} - HTTP/${req.httpVersion} - ${req.method} - ${secure ? 'https' : 'http'}://${domain}${req.url}`);

      const forwardReq = http.request(options, (forwardRes) => {
        const headersOut = { ...forwardRes.headers, 'otoroshi-state-resp': stateToken };  // TODO: handle v2 ???     
        res.writeHead(forwardRes.statusCode, headersOut);
        forwardRes.setEncoding('utf8');
        let ended = false;
        forwardRes.on('data', (chunk) => res.write(chunk));
        forwardRes.on('close', () => {
          res.end();
          if (!ended) {
            ended = true;
            span.end();
          }
        });
        forwardRes.on('end', () => {
          res.end();
          if (!ended) {
            ended = true;
            span.end();
          }
        });
      }).on('error', (err) => {
        console.log(`[EXTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Error while forwarding request`, err);
        if (err.type === 'ProxyError') {
          try {
            writeError(err.status, err.body, err.ctype, res, args);
          } catch (err2) {
            console.log(`[EXTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Could not send error response: `, err2, {});
          }
        } else {
          writeError(502, `{"error": "EXTERNAL error", "type":"EXTERNAL-PROXY", "message":"${err.message}" }`, 'application/json', res, args);
        }
      });
      const contentLength = parseInt(req.headers['Content-Length'] || req.headers['content-length'] || '0', 10);
      const hasContentLength = contentLength > 0;
      const isChunked = (req.headers['Transfer-Encoding'] || req.headers['transfer-encoding'] || 'none') === 'chunked';
      if (hasContentLength || isChunked) {
        req.on('data', (chunk) => forwardReq.write(chunk));
        req.on('close', () => forwardReq.end());
        req.on('end', () => forwardReq.end());
      } else {
        forwardReq.end();
      }
    } catch (_error) {
      console.log(`[EXTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} http request to service raised: `, _error, {});
      if (_error.type === 'ProxyError') {
        try {
          writeError(_error.status, _error.body, _error.ctype, res, args);
        } catch (_error2) {
          console.log(`[EXTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Could not send error response: `, _error2, {});
        }
      } else {
        try {
          writeError(502, `{"error": "EXTERNAL error", "type":"EXTERNAL-PROXY", "message":"${_error.message}" }`, 'application/json', res, args);
        } catch (_error2) {
          console.log(`[EXTERNAL-PROXY] ${moment().format('YYYY-MM-DD HH:mm:ss.SSS')} Could not send error response: `, _error2, {});
        }
      }
    }
  });

  return server;
}

exports.InternalProxy = InternalProxy;
exports.ExternalProxy = ExternalProxy;