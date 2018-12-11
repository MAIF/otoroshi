const fs = require('fs'); 
const https = require('https'); 
const x509 = require('x509');

const apps = [
  {
    "id": "iogOIDH09EktFhydTp8xspGvdaBq961DUDr6MBBNwHO2EiBMlOdafGnImhbRGy8z",
    "name": "my-web-service",
    "description": "A service that says hello",
    "host": "www.frontend.lol"
  }
];

const users = [
  {
    "name": "Mathieu",
    "email": "mathieu@foo.bar",
    "appRights": [
      {
        "id": "iogOIDH09EktFhydTp8xspGvdaBq961DUDr6MBBNwHO2EiBMlOdafGnImhbRGy8z",
        "profile": "user",
        "forbidden": false
      },
      {
        "id": "PqgOIDH09EktFhydTp8xspGvdaBq961DUDr6MBBNwHO2EiBMlOdafGnImhbRGy8z",
        "profile": "none",
        "forbidden": true
      },
    ],
    "ownedDevices": [
      "mbp-123456789",
      "nuc-987654321",
    ]
  }
];

const devices = [
  {
    "serialNumber": "mbp-123456789",
    "hardware": "Macbook Pro 2018 13 inc. with TouchBar, 2.6 GHz, 16 Gb",
    "acquiredAt": "2018-10-01",
  },
  {
    "serialNumber": "nuc-987654321",
    "hardware": "Intel NUC i7 3.0 GHz, 32 Gb",
    "acquiredAt": "2018-09-01",
  },
  {
    "serialNumber": "iphone-1234",
    "hardware": "Iphone XS, 256 Gb",
    "acquiredAt": "2018-12-01",
  }
];

const options = { 
  key: fs.readFileSync('./server/_.backend.lol.key'), 
  cert: fs.readFileSync('./server/_.backend.lol.cer'), 
  ca: fs.readFileSync('./ca/ca-backend.cer'), 
  requestCert: true, 
  rejectUnauthorized: true
}; 

function decodeBody(request) {
  return new Promise((success, failure) => {
    const body = [];
    request.on('data', (chunk) => {
      body.push(chunk);
    }).on('end', () => {
      const bodyStr = Buffer.concat(body).toString();
      success(JSON.parse(bodyStr));
    });
  });
}

function call(req, res) {
  decodeBody(req).then(body => {
    const service = body.service;
    const email = (body.user || { email: 'mathieu@foo.bar' }).email; // here, should not be null if used with an otoroshi auth. module
    const commonName = x509.getSubject(body.chain).commonName
    const device = devices.filter(d => d.serialNumber === commonName)[0];
    const user = users.filter(d => d.email === email)[0];
    const app = apps.filter(d => d.id === service.id)[0];
    res.writeHead(200, {
      'Content-Type': 'application/json'
    }); 
    if (user && device && app) {
      const userOwnsDevice = user.ownedDevices.filter(d => d === device.serialNumber)[0];
      const rights = user.appRights.filter(d => d.id === app.id)[0];
      const hasRightToUseApp = !rights.forbidden
      if (userOwnsDevice && hasRightToUseApp) {
        console.log(`Call from user "${user.email}" with device "${device.hardware}" on app "${app.name}" with profile "${rights.profile}" authorized`)
        res.end(JSON.stringify({ status: 'good', profile: rights.profile }) + "\n"); 
      } else {
        console.log(`Call from user "${user.email}" with device "${device.hardware}" on app "${app.name}" unauthorized because user doesn't owns the hardware or has no rights`)
        res.end(JSON.stringify({ status: 'unauthorized' }) + "\n"); 
      }
    } else {
      console.log(`Call unauthorized`)
      res.end(JSON.stringify({ status: 'unauthorized' }) + "\n"); 
    }
  });
}

https.createServer(options, call).listen(8445);