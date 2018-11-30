// nvm install 11.3.0 or nvm use 11.3.0
const http2 = require('http2');
const fs = require('fs');

const KEY_PATH = process.env.KEY_PATH;
const CERT_PATH = process.env.CERT_PATH;

const server = http2.createSecureServer({
  key: fs.readFileSync(KEY_PATH),
  cert: fs.readFileSync(CERT_PATH)
});
server.on('error', (err) => console.error(err));

server.on('stream', (stream, headers) => {
  stream.respond({
    'content-type': 'text/html',
    ':status': 200
  });
  stream.end('<h1>Hello World!</h1>');
});

server.listen(8443);