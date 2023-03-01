const fs = require('fs');
const https = require('https');

function Backend(opts) {

  const server = https.createServer({
    key: opts.key,
    cert: opts.cert,
    ca: opts.ca,
    rejectUnauthorized: opts.rejectUnauthorized,
    requestCert: opts.requestCert,
    maxVersion: 'TLSv1.2'
  }, (req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write(JSON.stringify({ hello: 'hello' }));
    res.end();
  });

  return {
    listen: (lopts, f) => {
      return server.listen(lopts.port, f);
    }
  };
}

function client(mtls) {
  return new Promise((success, failure) => {
    const options = {
      hostname: 'api-test.oto.tools',
      port: 3333,
      path: '/test',
      method: 'GET',
      headers: {
        host: 'api-test.oto.tools',
      },
      ca: fs.readFileSync('./certs/ca.cer'),
      key: mtls ? fs.readFileSync('./certs/client.key') : null,
      cert: mtls ? fs.readFileSync('./certs/client.cer') : null
    };

    const request = https.request(options, (resp) => {
      const response = {
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
        console.log(JSON.stringify(response, null, 2));
        success();
      })
    }).on('error', (e) => {
      console.log('client error', e)
      failure(e);
    });
    request.end();
  });
}

const mtls = !process.argv.slice(2).includes('mtls_off');
const noexit = process.argv.slice(2).includes('no_exit');
const noclient = process.argv.slice(2).includes('no_client');

Backend({
  key:  fs.readFileSync('./certs/backend.key'),
  cert: fs.readFileSync('./certs/backend.cer'),
  ca:   fs.readFileSync('./certs/ca.cer'),
  rejectUnauthorized: mtls,
  requestCert: mtls,
}).listen({ port: 3333 }, () => {
  console.log("server listening at https://127.0.0.1:3333")
  console.log(`mtls is ${mtls ? 'on' : 'off'}`);
  console.log('')
  if (!noclient) {
    setTimeout(() => {
      client(mtls).then(() => {
        if (!noexit) process.exit(0);
      });
    }, 2000)
  }
})
