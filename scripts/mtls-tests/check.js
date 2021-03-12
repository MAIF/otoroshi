const fs = require('fs');

const clientbackend = fs.readFileSync('./clientbackend.out').toString('utf8');
const clientfrontend = fs.readFileSync('./clientfrontend.out').toString('utf8');
const foundbackend = clientbackend.indexOf('Hello, world!') > -1;
const foundfrontend = clientfrontend.indexOf('Hello, world!') > -1;
console.log(`clientbackend: ${clientbackend}`)
console.log(`clientfrontend: ${clientfrontend}`)
if (foundbackend && foundfrontend) {
  console.log('mTLS check successful !');
  process.exit(0);
} else {
  console.log('mTLS check failed :(');
  process.exit(-1);
}