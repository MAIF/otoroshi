var http = require('http');
http.createServer(function (req, res) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.write(JSON.stringify(req.headers));
  res.end();
}).listen(3000);