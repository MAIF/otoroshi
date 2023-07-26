const http = require('http')

http.createServer((r, s) => {
  if (r.method === 'POST' && r.headers['content-length']) {
    let body = '';
    r.on('data', (chunk) => {
      body += chunk;
    });
    r.on('end', () => {
      console.log(r.method, r.url, r.headers, body);
      s.writeHead(200, { 'Content-Type': 'application/json' });
      s.write(JSON.stringify({ done: true })); 
      s.end(); 
    });
  } else {
    console.log(r.method, r.url, r.headers);
  }
}).listen(10080); 
