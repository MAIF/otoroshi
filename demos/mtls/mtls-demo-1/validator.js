const fs = require('fs'); 
const http = require('http'); 
const https = require('https'); 

const users = [
  {
    "name": "Mathieu",
    "email": "mathieu@foo.bar",
    "certificateFingerprint": "",
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
    "certificateFingerPrint": "d3b38e04a8ca1e40b965ab6c73b95b21edb27cbd"
  },
  {
    "serialNumber": "nuc-987654321",
    "hardware": "Intel NUC i7 3.0 GHz, 32 Gb",
    "acquiredAt": "2018-09-01",
    "certificateFingerPrint": "856140ce54a1655de6b3aae90b255f8c94234c99"
  },
  {
    "serialNumber": "iphone-1234",
    "hardware": "Iphone XS, 256 Gb",
    "acquiredAt": "2018-12-01",
    "certificateFingerPrint": "58430fe752b158f16fadaaf061bd03f0c9641a2f"
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
    const email = (body.user || { email: 'mathieu@foo.bar' }).email; // here, should not be null if used with an otoroshi auth. module
    const fingerprint = body.fingerprints[0];
    const device = devices.filter(d => d.certificateFingerPrint === fingerprint)[0];
    const user = users.filter(d => d.email === email)[0];
    res.writeHead(200, {
      'Content-Type': 'application/json'
    }); 
    if (user && device) {
      const userOwnsDevice = user.ownedDevices.filter(d => d === device.serialNumber)[0];
      if (userOwnsDevice) {
        console.log(`Call from user ${user.email} with device ${device.hardware} authorized`)
        res.end(JSON.stringify({ status: 'good' }) + "\n"); 
      } else {
        console.log(`Call from user ${user.email} with device ${device.hardware} unauthorized because user doesn't owns the hardware`)
        res.end(JSON.stringify({ status: 'unauthorized' }) + "\n"); 
      }
    } else {
      console.log(`Call unauthorized`)
      res.end(JSON.stringify({ status: 'unauthorized' }) + "\n"); 
    }
  });
}

http.createServer(call).listen(8447);
https.createServer(options, call).listen(8445);