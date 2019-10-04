const fs = require('fs');

const clientbackend = fs.readFileSync('./clientbackend.go').toString('utf8');
const clientfrontend = fs.readFileSync('./clientfrontend.go').toString('utf8');
const foundbackend = clientbackend.indexOf('Hello, world!') > -1;
const foundfrontend = clientfrontend.indexOf('Hello, world!') > -1;
if (foundbackend && foundfrontend) {
  process.exit(0);
} else {
  process.exit(-1);
}