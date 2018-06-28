const express = require('express');
const parsers = require('body-parser');
const app = express();
const faker = require('faker');
const colors = require('colors');
const fetch = require('node-fetch');
const readline = require('readline');
const _ = require('lodash');
const argv = require('minimist')(process.argv.slice(2));

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

const port = process.env.PORT || '8888';
app.use(parsers.json(), parsers.text());
app.get('/*', (req, res) => res.status(200).send({ message: `Hello at ${Date.now()}` }));
app.post('/*', (req, res) => {
  const body = req.body;
  console.log("'" + body + "'");
  res.status(200).send({ message: `Hello at ${Date.now()}`, body });
});
app.listen(port, () => console.log(`serving API on http://0.0.0.0:${port}\n`));

const host = 'outaged.dev.opunmaif.fr';
const location = '127.0.0.1:9999';
const stdout = process.stdout;
const byResult = {
  '2xx': 0,
  '3xx': 0,
  '4xx': 0,
  '5xx': 0,
  'mean resp. time': 0,
  'mean resp. size': 0,
};
const lastResult = {};
let respTimes = [];
let respSize = [];
let errors = 0;
let lastNLines = 0;

for (let i = 0; i < 100; i++) {
  console.log(' ');
}
console.log(`\nInjecting traffic on http://${host}/ @ ${location}\n`);

function callApi() {
  const start = Date.now();
  return fetch(`http://${location}/`, {
    method: 'GET',
    headers: {
      Host: host,
      Accept: 'application/json',
    },
  })
    .then(
      r => {
        respTimes = [Date.now() - start, ...respTimes];
        const status = r.status;
        if (status > 199 && status < 300) {
          byResult['2xx'] = byResult['2xx'] + 1;
        }
        if (status > 299 && status < 400) {
          byResult['3xx'] = byResult['3xx'] + 1;
        }
        if (status > 399 && status < 500) {
          byResult['4xx'] = byResult['4xx'] + 1;
        }
        if (status > 499 && status < 600) {
          byResult['5xx'] = byResult['5xx'] + 1;
        }
        r.text().then(t => {
          const json = JSON.parse(t);
          respSize = [t.length, ...respSize];
        });
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
  respTimes = _.take(respTimes, 100);
  respSize = _.take(respSize, 100);
  byResult['mean resp. time'] = (respTimes.reduce((a, b) => a + b, 0) / respTimes.length).toFixed(
    2
  );
  byResult['mean resp. size'] = (respSize.reduce((a, b) => a + b, 0) / respSize.length).toFixed(2);
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

const clients = argv.clients || 0;

_.range(0, clients).map(c => callApi());

const shouldDisplayState = argv.displayState ? argv.displayState === 'true' : true;
if (shouldDisplayState) {
  setTimeout(displayState, 200);
}
