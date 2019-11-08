const express = require('express');
const app = express();
const faker = require('faker');
const colors = require('colors');
const fetch = require('node-fetch');
const readline = require('readline');
const _ = require('lodash');
const argv = require('minimist')(process.argv.slice(3));

const CLEAR_WHOLE_LINE = 0;

function clearLine(stdout) {
  readline.clearLine(stdout, CLEAR_WHOLE_LINE);
  readline.cursorTo(stdout, 0);
}

function clearNthLine(stdout, n) {
  if (n == 0) {
    clearLine(stdout);
    return;
  }
  readline.cursorTo(stdout, 0);
  readline.moveCursor(stdout, 0, -n);
  readline.clearLine(stdout, CLEAR_WHOLE_LINE);
  readline.moveCursor(stdout, 0, n);
}

function formatCount(count, color) {
  const size = String(count).length;
  const dots = _.range(0, 10 - size)
    .map(() => ' ')
    .join('');
  return '(' + dots + count[color] + ')';
}

function writeLine(stdout, name, color, count, changed) {
  const first = name[color];
  const size = 5 + name.length;
  const dots = _.range(0, 60 - size)
    .map(() => '.')
    .join('');
  if (changed) {
    stdout.write(` * ${name[color]} ${dots} ${formatCount(count, color)}\n`);
  } else {
    stdout.write(` * ${name[color]} ${dots} ${formatCount(count, 'white')}\n`);
  }
}

const action = process.argv[2];

if (action === 'server') {
  const port = argv.port || 8081;
  const name = argv.name || faker.name.firstName() + ' ' + faker.name.lastName();
  app.get('/*', (req, res) => res.status(200).send({ message: `Hello from ${name}`, name }));
  app.listen(port, () => console.log(`serving API with name '${name}' on http://0.0.0.0:${port}`));
} else if (action === 'injector') {
  const host = String(argv.host || 'api.oto.tools');
  const location = String(argv.location || '127.0.0.1:8080');
  const clientId = String(argv.clientId || '--');
  const clientSecret = String(argv.clientSecret || '--');
  const stdout = process.stdout;
  const byResult = {};
  const lastResult = {};

  let errors = 0;
  let lastNLines = 0;

  for (let i = 0; i < 100; i++) {
    console.log(' ');
  }
  console.log(`\nInjecting traffic on http://${host}/ @ ${location}\n`);

  function callApi() {
    return fetch(`http://${location}/`, {
      method: 'GET',
      headers: {
        Host: host,
        Accept: 'application/json',
        'Otoroshi-Client-Id': clientId || '--',
        'Otoroshi-Client-Secret': clientSecret || '--',
      },
    })
      .then(
        r => {
          if (r.status === 200) {
            return r.json().then(json => {
              const name = json.name;
              if (byResult[name]) {
                byResult[name] = byResult[name] + 1;
                return byResult[name];
              } else {
                byResult[name] = 1;
                return byResult[name];
              }
            });
          } else {
            // r.text().then(t => console.log(t, r.status))
            errors = errors + 1;
            return;
          }
        },
        e => {
          console.log(e);
          errors = errors + 1;
          return;
        }
      )
      .then(() => {
        setTimeout(callApi, 50);
      });
  }

  function displayState() {
    const keys = Object.keys(byResult);
    clearNthLine(stdout, lastNLines);
    lastNLines = 0 - (keys.length + 1);
    keys.forEach(key => {
      const res = String(byResult[key]);
      const changed = lastResult[key] !== res;
      lastResult[key] = res;
      writeLine(stdout, key, 'green', res, changed);
    });
    const res = String(errors);
    const changed = lastResult['ERRORS'] !== res;
    lastResult['ERRORS'] = res;
    writeLine(stdout, 'ERRORS', 'red', String(errors), changed);
    setTimeout(displayState, 500);
  }

  callApi();
  setTimeout(displayState, 500);
} else {
  console.log(`Unkown action: ${action}. Usage is 'node server.js (server|injector) ...args'`);
}
