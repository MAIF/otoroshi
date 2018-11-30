// nvm install 11.3.0 or nvm use 11.3.0
const http2 = require('http2');
const fs = require('fs');
const CA_PATH = process.env.CA_PATH;
const URL = process.env.URL;

const client = http2.connect(URL, {
  ca: fs.readFileSync(CA_PATH)
});
client.on('error', (err) => console.error(err));

const req = client.request({ ':path': '/' });

req.on('response', (headers, flags) => {
  for (const name in headers) {
    console.log(`${name}: ${headers[name]}`);
  }
});

req.setEncoding('utf8');
let data = '';
req.on('data', (chunk) => { data += chunk; });
req.on('end', () => {
  console.log(`\ndata:\n\n${data}\n`);
  client.close();
});
req.end();