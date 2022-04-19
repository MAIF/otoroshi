var http = require('http');
http.createServer(function (req, res) {

  let data = null;

  req.on('data', chunk => {
    if (!data) {
      data = '';
    }
    data += chunk;
  });

  req.on('end', () => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write(JSON.stringify({ headers: req.headers, method: req.method, path: req.url, body: data }));
    res.end();
  });

}).listen(3000);