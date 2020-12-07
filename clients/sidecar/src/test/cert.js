const http = require('http');
const fs = require('fs');

function getCert(query, path) {
  return new Promise((success, failure) => {
    const options = {
      hostname: '127.0.0.1',
      port: 9999,
      path,
      method: 'POST',
      headers: {
        host: 'otoroshi-api.oto.tools',
        'otoroshi-client-id': 'admin-api-apikey-id',
        'otoroshi-client-secret': 'admin-api-apikey-secret',
      }
    };
    const request = http.request(options, (resp) => {
      let data = '';
      resp.on('data', chunk => {
        data = data + chunk
      });
      resp.on('end', () => {
        success(JSON.parse(data))
      });
    }).on('error', (e) => {
      console.log('getSelfSignedCA error', e);
      failure(e);
    });
    request.write(Buffer.from(JSON.stringify(query)));
    request.end();
  });
}

function genQuery(subj, duration, ca, client) {
  return {
    "hosts": [],
    "key": {
      "algo": 2048
    },
    "name": {},
    "subject": subj,
    "client": client,
    "ca": ca,
    "duration": duration,
    "signatureAlg": 'SHA256WithRSAEncryption',
    "digestAlg": 'SHA-256',
  }
}

async function test() {

  const root    = await getCert(genQuery('CN=root', 10 * 365 * 24 * 60 * 1000, true, false), '/api/pki/cas')
  const subca   = await getCert(genQuery('CN=subca', 10 * 365 * 24 * 60 * 1000, true, false), `/api/pki/cas/${root.certId}/cas`)
  // const subca = root;
  const oto     = await getCert(genQuery('CN=api.oto.tools', 365 * 24 * 60 * 1000, false, false), `/api/pki/cas/${subca.certId}/certs`)
  const backend = await getCert(genQuery('CN=api-test.oto.tools', 365 * 24 * 60 * 1000, false, false), `/api/pki/cas/${subca.certId}/certs`)
  const client  = await getCert(genQuery('CN=api-test.oto.tools', 365 * 24 * 60 * 1000, false, true), `/api/pki/cas/${subca.certId}/certs`)
  const otoCli  = await getCert(genQuery('CN=api.oto.tools', 365 * 24 * 60 * 1000, false, true), `/api/pki/cas/${subca.certId}/certs`)

  function writeFiles(path, cert, chain = []) {
    fs.writeFileSync(path + '.cer', cert.cert + '\n' + chain.map(c => c.cert).join('\n'));
    fs.writeFileSync(path + '.key', cert.key);
    fs.writeFileSync(path + '.csr', cert.csr);
  }

  writeFiles('./certs/ca', root);
  writeFiles('./certs/subca', subca, [root]);
  const chain = [subca, root];
  // const chain = [root];
  writeFiles('./certs/oto', oto, chain);
  writeFiles('./certs/backend', backend, chain);
  writeFiles('./certs/client', client, chain);
  writeFiles('./certs/oto-client', otoCli, chain);
  fs.writeFileSync('./certs/foo', '')
}

test();

