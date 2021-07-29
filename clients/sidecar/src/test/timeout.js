const http = require('http');

function Backend(opts) {

  function queryParams(req) {
    let q = req.url.split('?')
    let result = {};
    if (q.length >=2 ){
      q[1].split('&').forEach((item) => {
          try {
            result[item.split('=')[0]] = item.split('=')[1];
          } catch (e) {
            result[item.split('=')[0]] = null;
          }
      })
    }
    return result;
  }

  const server = http.createServer((req, res) => {
    const timeout = parseInt(queryParams(req).timeout || '30000')
    setTimeout(() => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.write(JSON.stringify({ 'msg': 'hello ' + Date.now() }));
      res.end();
    }, timeout);
  });

  return {
    listen: (lopts) => {
      return server.listen(lopts.port, lopts.hostname);
    }
  };
}

Backend().listen({ port: 3001, hostname: '0.0.0.0'})