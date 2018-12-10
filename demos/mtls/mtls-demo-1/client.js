const fs = require('fs'); 
const https = require('https'); 

process.env['NODE_TLS_REJECT_UNAUTHORIZED'] = 0;

const options = { 
  hostname: 'api.frontend.lol', 
  port: 8443, 
  path: '/', 
  method: 'GET', 
  key: fs.readFileSync('./client/_.frontend.lol.key'), 
  cert: fs.readFileSync('./client/_.frontend.lol.cer'), 
  ca: fs.readFileSync('./ca/ca-frontend.cer'), 
}; 

const req = https.request(options, (res) => { 
  console.log('statusCode:', res.statusCode);
  console.log('headers:', res.headers);
  res.on('data', (data) => { 
    process.stdout.write(data); 
  }); 
}); 

req.end(); 

req.on('error', (e) => { 
  console.error(e); 
});