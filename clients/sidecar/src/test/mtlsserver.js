const fs = require('fs');
const https = require('https');

function Backend(opts) {

  const server = https.createServer({
    key: opts.key,
    cert: opts.cert,
    ca: opts.ca,
    rejectUnauthorized: opts.rejectUnauthorized,
    requestCert: opts.requestCert  
  }, (req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write(JSON.stringify({ hello: 'hello' }));
    res.end();
  });

  return {
    listen: (lopts) => {
      return server.listen(lopts.port);
    }
  };
}

Backend({
  key:  fs.readFileSync('./certs/backend.key'),
  cert: fs.readFileSync('./certs/backend.cer'),
  ca:   fs.readFileSync('./certs/ca.cer'),
  rejectUnauthorized: true,
  requestCert: true,
}).listen({ port: 3333 })