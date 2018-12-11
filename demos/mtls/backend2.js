const fs = require('fs'); 
const https = require('https'); 

const options = { 
  key: fs.readFileSync('./server/_.backend.lol.key'), 
  cert: fs.readFileSync('./server/_.backend.lol.cer'), 
  ca: fs.readFileSync('./ca/ca-backend.cer'), 
  requestCert: true, 
  rejectUnauthorized: true
}; 

https.createServer(options, (req, res) => { 
  console.log('Client certificate CN:', req.socket.getPeerCertificate().subject.CN);
  res.writeHead(200, {
    'Content-Type': 'text/html'
  }); 
  res.end('<h1>Hello World !!!</h1>\n'); 
}).listen(8446);