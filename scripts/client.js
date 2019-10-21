const https = require('https');
const http = require('http');

function repeat(char, length) {
  const chars = [];
  for (let i = 0; i < length; i++) {
    chars.push(char);
  }
  return chars.join('');
}

http.get('http://otorshi-api.oto.tools:8080/api/services/' + repeat('a', (68 * 1024) - 100), (resp) => {
  let data = '';
  resp.on('data', (chunk) => {
    data += chunk;
  });
  resp.on('end', () => {
    console.log(resp.statusCode)
    console.log(resp.headers);
    if (resp.statusCode === 414) {
      console.log(data);
    }
  });
}).on("error", (err) => {
  console.log("Error: " + err.message);
});