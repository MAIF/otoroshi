const fs = require('fs'); 
const net = require('net');
const fetch = require('node-fetch');
const https = require('https');
const faker = require('faker');
const HttpsProxyAgent = require('https-proxy-agent');
const WebSocket = require('ws');
const open = require('open');

const cliOptions = require('minimist')(process.argv.slice(2));
const proxy = process.env.https_proxy || process.env.http_proxy || cliOptions.proxy;
const debug = cliOptions.debug || false;
const prompt = cliOptions.prompt || 'readlinesync';
const clientCaPath = cliOptions.caPath;
const clientCertPath = cliOptions.certPath;
const clientKeyPath = cliOptions.keyPath;

const AgentClass = !!proxy ? HttpsProxyAgent : https.Agent;
const proxyUrl = !!proxy ? url.parse(proxy): {};
const agent = (clientCaPath || clientCertPath || clientKeyPath) ? new AgentClass({
  ...proxyUrl,
  key: clientKeyPath ? fs.readFileSync(clientKeyPath) : undefined,
  cert: clientCertPath ? fs.readFileSync(clientCertPath) : undefined,
  ca: clientCaPath ? fs.readFileSync(clientCaPath) : undefined,
}) : undefined;

function debugLog(...args) {
  if (debug) {
    console.log(...args);
  }
}

require('readline').emitKeypressEvents(process.stdin);

function askForToken(sessionId, cb) {
  if (prompt === 'readlinesync') {
    const token = require('readline-sync').question(`[${sessionId}] Session token > `, {
      //hideEchoBack: true // The typed text on screen is hidden by `*` (default).
    });
    cb(token);
  } else if (prompt === 'readline') {
    const readline = require('readline').createInterface({
      input: process.stdin,
      output: process.stdout,
      prompt: `[${sessionId}] Session token > `,
      crlfDelay: Infinity
    });
    readline.on('line', (line) => {
      const token = line.trim();
      readline.close();
      cb(token);
    });
    readline.prompt();
  } else if (prompt === 'inquirer') {
    const questions = [{
      type: 'input',
      name: 'token',
      message: `[${sessionId}] Session token > `,
    }];
    require('inquirer').prompt(questions).then(answers => {
      cb(answers['token']);
    });
  }
}

function ApiKeyAuthChecker(remoteUrl, headers) {

  function check() {
    return new Promise((success, failure) => {
      fetch(`${remoteUrl}/.well-known/otoroshi/me`, {
        method: 'GET',
        headers: { ...headers, 'Accept': 'application/json' }
      }).then(r => {
        if (r.status === 200) {
          r.json().then(json => {
            success(json);
          });
        } else {
          r.text().then(text => {
            failure(text);
          });
        }
      }).catch(e => {
        failure(e);
      });
    });
  }

  function every(value, onFailure) {
    const interval = setInterval(() => check().catch(e => {
      onFailure(e);
      clearInterval(interval);
    }), value);
    return () => {
      clearInterval(interval);
    };
  }

  return {
    check,
    every
  };
}

function SessionAuthChecker(remoteUrl, token) {
  
  function check() {
    return new Promise((success, failure) => {
      fetch(`${remoteUrl}/.well-known/otoroshi/me?pappsToken=${token}`, {
        method: 'GET',
        headers: { 'Accept': 'application/json' }
      }).then(r => {
        if (r.status === 200) {
          r.json().then(json => {
            success(json);
          });
        } else {
          r.text().then(text => {
            failure(text);
          });
        }
      }).catch(e => {
        failure(e);
      });
    });
  }

  function every(value, onFailure) {
    const interval = setInterval(() => check().catch(e => {
      onFailure(e);
      clearInterval(interval);
    }), value);
    return () => {
      clearInterval(interval);
    };
  }

  return {
    check,
    every
  };
}

function ProxyServer(options) {

  if (!options.remote) {
    throw new Error('No remote service location specified !');
  }

  const remoteWsUrl = options.remote.replace('http://', 'ws://').replace('https://', 'wss://');
  const remoteUrl = options.remote;
  const localProcessAddress = options.address || '127.0.0.1';
  const localProcessPort = options.port || 2222;
  const checkEvery = options.every || 10000;
  const access_type = options.access_type || "public";
  const apikey = options.apikey;
  const simpleApikeyHeaderName = options.sahn || 'x-api-key';
  const sessionId = options.name || faker.random.alphaNumeric(6);

  const headers = {};
  let finalUrl = remoteWsUrl + '/.well-known/otoroshi/tunnel';

  function startLocalServer() {

    const server = net.createServer((socket) => {

      socket.setKeepAlive(true, 60000);

      debugLog(`New client connected with session id: ${sessionId} on ${finalUrl}`);
      let clientConnected = false;
      const clientBuffer = [];
      const client = new WebSocket(finalUrl, {
        agent, 
        headers
      });
      // tcp socket callbacks
      socket.on('end', () => {
        debugLog(`Client deconnected (end) from session ${sessionId}`);
        client.close();
      });
      socket.on('close', () => {
        debugLog(`Client deconnected (close) from session ${sessionId}`);
        client.close();
      });
      socket.on('error', (err) => {
        debugLog(`Client deconnected (error) from session ${sessionId}`, err);
        client.close();
      });
      socket.on('data', (data) => {
        if (clientConnected) {
          debugLog(`Receiving client data from session ${sessionId}: ${data.length} bytes`);
          client.send(data);
        } else {
          debugLog(`Receiving client data from session ${sessionId}: ${data.length} bytes stored in buffer`);
          clientBuffer.push(data);
        }
      });
      // client callbacks
      client.on('open', () => {
        debugLog(`WS Client connected from session ${sessionId}`);
        if (clientBuffer.length > 0) {
          while (clientBuffer.length > 0) {
            const bytes = clientBuffer.shift();
            if (bytes) {
              client.send(bytes);
            }
          }
          debugLog(`WS Client buffer emptied for ${sessionId}`);
        } 
        clientConnected = true;
      });
      client.on('message', (payload) => {
        debugLog(`Data received from server from session ${sessionId}: ${payload.length} bytes`);
        if (payload) {
          if (payload.length > 0) {
            socket.write(payload);
          }
        }
      });
      client.on('error', (error) => {
        debugLog(`WS Client error from session ${sessionId}`, error);
        socket.destroy();
        clientConnected = false;
      });
      client.on('close', () => {
        debugLog(`WS Client closed from session ${sessionId}`);
        socket.destroy();
        clientConnected = false;
      });
    });

    server.on('error', (err) => {
      console.log(`tcp tunnel client error`, err);
    });

    server.listen(localProcessPort, localProcessAddress, () => {
      console.log(`[${sessionId}] Local tunnel listening on tcp://${localProcessAddress}:${localProcessPort} and targeting ${remoteWsUrl}\n`);
    });

    return server;
  }

  function start() {

    if (access_type === 'apikey') {
      if (apikey.indexOf(":") > -1) {
        headers['Authorization'] = `Basic ${Buffer.from(apikey).toString('base64')}`;
      } else {
        headers[simpleApikeyHeaderName] = apikey;
      }
      const checker = ApiKeyAuthChecker(remoteUrl, headers);
      return checker.check().then(() => {
        console.log(`[${sessionId}] Will use apikey authentication to access the service. Apikey access was successful !`);
        const server = startLocalServer();
        checker.every(checkEvery, () => {
          console.log(`[${sessionId}] Cannot access service with apikey anymore. Stopping the tunnel !`);
          server.close();
        });
        return server;
      }, text => {
        console.log(`[${sessionId}] Cannot access service with apikey. An error occurred`, text);
      });
    }

    if (access_type === 'session') {
      return open(`${remoteUrl}/?redirect=urn:ietf:wg:oauth:2.0:oob`).then(ok => {
        return new Promise(success => {
          askForToken(sessionId, token => {
            const checker = SessionAuthChecker(remoteUrl, token);
            finalUrl = finalUrl + '/?pappsToken=' + token;
            checker.check().then(() => {
              console.log(`[${sessionId}] Will use session authentication to access the service. Session access was successful !`);
              const server = startLocalServer();
              success(server);
              checker.every(checkEvery, () => {
                console.log(`[${sessionId}] Cannot access service with session anymore. Stopping the tunnel !`);
                server.close();
                ProxyServer(options).start();
              });
            }, text => {
              console.log(`[${sessionId}] Cannot access service with session. An error occurred`, text);
            });
          });
        });
      });
    }

    if (access_type === 'public') {
      return new Promise(s => {
        const server = startLocalServer();
        s(server);
      });
    }

    return Promise.reject(new Error('No legal access_type found (possible value: apikey, session, public)!'));
  }

  return {
    start
  };
}

function asyncForEach(_arr, f) {
  return new Promise((success, failure) => {
    const arr = [ ..._arr ];
    function next() {
      const item = arr.shift();
      if (item) {
        const res = f(item);
        if (res && res.then) {
          res.then(() => {
            next();
          })
        } else {
          setTimeout(() => next(), 10);
        }
      } else {
        success();
      }
    }
    next();
  });
}

if (cliOptions.config && fs.existsSync(cliOptions.config)) {
  const configContent = fs.readFileSync(cliOptions.config).toString('utf8');
  const configJson = JSON.parse(configContent);
  const items = (configJson.tunnels || configJson).filter(item => item.enabled);
  if (configJson.name) {
    console.log(`Launching tunnels for "${configJson.name}" configuration file located at "${cliOptions.config}"\n`)
  }
  asyncForEach(items, item => {
    return ProxyServer(item).start();
  });
} else {
  ProxyServer(cliOptions).start();
}