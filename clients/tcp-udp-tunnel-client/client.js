const fs = require('fs'); 
const net = require('net');
const dgram = require('dgram');
const fetch = require('node-fetch');
const https = require('https');
const faker = require('faker');
const HttpsProxyAgent = require('https-proxy-agent');
const WebSocket = require('ws');
const open = require('open');
const moment = require('moment');
const colors = require('colors');
const _ = require('lodash');

const cliOptions = require('minimist')(process.argv.slice(2));
const proxy = process.env.https_proxy || process.env.http_proxy || cliOptions.proxy;
const debug = cliOptions.debug || false;
const prompt = cliOptions.prompt || 'readline';
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

const possibleColors = [
  'green',
  'yellow',
  'blue',
  'red',
  'magenta',
  'cyan',
  'white',
  'grey',
];

/*****************************************************/
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
/*****************************************************/
const awaitingReconnections = [];
function reconnectAwaitingReconnections() {
  if (awaitingReconnections.length > 0) {
    const reconnect = awaitingReconnections.shift();
    if (reconnect) {
      try {
        reconnect().then(() => {
          setTimeout(reconnectAwaitingReconnections, 2000);
        }).catch(e => {
          setTimeout(reconnectAwaitingReconnections, 2000);
        });
      } catch (e) {
        setTimeout(reconnectAwaitingReconnections, 2000);
      }
    } else {
      setTimeout(reconnectAwaitingReconnections, 2000);
    }
  } else {
    setTimeout(reconnectAwaitingReconnections, 2000);
  }
}
setTimeout(reconnectAwaitingReconnections, 2000);
/*****************************************************/
const existingSessionTokens = {};
/*****************************************************/
function debugLog(...args) {
  if (debug) {
    console.log(...args);
  }
}
/*****************************************************/
let runningInDocker = false;
if (fs.existsSync('/proc/self/cgroup')) {
  const content = fs.readFileSync('/proc/self/cgroup').toString('utf8');
  runningInDocker = !!(content.split("\n").filter(line => {
    return !!(line.split('/').filter(part => part === 'docker')[0]);
  })[0]);
  if (runningInDocker) {
    console.log(`When running inside docker, browser integration will not work, you'll have to copy/paste URL in your browser when asked.`)
  }
}
/*****************************************************/
require('readline').emitKeypressEvents(process.stdin);
/*****************************************************/

function askForToken(sessionId, color, cb) {
  if (prompt === 'readlinesync') {
    const token = require('readline-sync').question(color(`[${sessionId}]`) + ` Session token > `.white.bold, {
      //hideEchoBack: true // The typed text on screen is hidden by `*` (default).
    });
    cb(token);
  } else if (prompt === 'readline') {
    const readline = require('readline').createInterface({
      input: process.stdin,
      output: process.stdout,
      prompt: color(`[${sessionId}]`) + ` Session token > `.white.bold,
      crlfDelay: Infinity
    });
    readline.on('line', (line) => {
      if (line.trim() === '') {
        readline.prompt();
      } else {
        const token = line.trim();
        readline.close();
        cb(token);
      }
    });
    readline.prompt();
  } else if (prompt === 'inquirer') {
    const questions = [{
      type: 'input',
      name: 'token',
      message: color(`[${sessionId}]`) + ` Session token > `.white.bold,
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

function SessionAuthChecker(remoteUrl, token, headers) {
  
  function check() {
    return new Promise((success, failure) => {
      fetch(`${remoteUrl}/.well-known/otoroshi/me?pappsToken=${token}`, {
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

function ProxyServer(options, optionalConfigFile) {

  const color = colors[possibleColors[Math.floor(Math.random() * possibleColors.length)]].bold;
  const sessionId = options.name || faker.random.alphaNumeric(6);

  if (!options.remote) {
    console.warn(color(`[${sessionId}]`) + ` No remote service location specified with the --remote flag !`);
    console.log('');
    displayHelpText();
    process.exit(-1);
  }

  const remoteWsUrl = options.remote.replace('http://', 'ws://').replace('https://', 'wss://');
  const remoteUrl = options.remote;
  let localProcessAddress = options.address || '127.0.0.1';
  const localProcessPort = options.port || 2222;
  const checkEvery = options.every || 10000;
  const access_type = options.access_type;
  const simpleApikeyHeaderName = options.sahn || 'x-api-key';
  const remoteHost = options.remoteHost;
  const remotePort = options.remotePort;
  let transport = options.transport || 'tcp';
  let dgramType = transport;
  if (transport === 'udp6') {
    transport = 'udp';
    dgramType = 'udp6';
    localProcessAddress = net.isIPv6(localProcessAddress) ? localProcessAddress : '::0';
  } else if (transport === 'udp4') {
    transport = 'udp';
    dgramType = 'udp4';
  } else if (transport === 'udp') {
    dgramType = 'udp4';
  }

  let apikey = options.apikey;

  const headers = {};
  let finalUrl = remoteWsUrl + '/.well-known/otoroshi/tunnel';

  function tryExistingTokenBeforeRelogin(sessionId, remoteUrl) {
    let done = false;
    return new Promise((success, failure) => {
      asyncForEach(Object.keys(existingSessionTokens), token => {
        return SessionAuthChecker(remoteUrl, token).check().then(r => {
          if (!done) {
            success(token);
          }
        });
      }).then(() => {
        if (!done) {
          success(null);
        }
      });
    });
  }

  function startLocalProxy() {
    if (transport === 'tcp') {
      return startLocalTcpProxy();
    } else {
      return startLocalUdpProxy();
    }
  }

  function startLocalTcpProxy() {

    if (options.remote.indexOf('http://') === 0) {
      console.warn(color(`[${sessionId}]`) + ` You are using an insecure connection to '${options.remote}'. Please consider using '${options.remote.replace('http://', 'https://')}' to increase tunnel security.`.red)
    }

    let activeConnections = 0;

    const server = net.createServer((socket) => {

      socket.setKeepAlive(true, 60000);
      activeConnections = activeConnections + 1;
      const connectionId = faker.random.alphaNumeric(6);
      console.log(color(`[${sessionId}]`) + ` New connection (${connectionId}). ${activeConnections} active connections.`);
      debugLog(`New client connected with session id: ${sessionId} on ${finalUrl}`);
      let closed = false;
      let clientConnected = false;
      const clientBuffer = [];
      const remoteArgs = _.entries({
        remoteHost,
        remotePort,
        transport: 'tcp'
      }).filter(e => !!e[1]).map(e => `${e[0]}=${e[1]}`).join('&');
      const wsUrl = finalUrl.indexOf('?') > -1 ? finalUrl + '&' + remoteArgs: finalUrl + '?' + remoteArgs;
      const client = new WebSocket(wsUrl, {
        agent, 
        headers
      });

      function displayEndOfSession() {
        if (!closed) {
          closed = true;
          activeConnections = activeConnections - 1;
          console.log(color(`[${sessionId}]`) + ` One connection closed (${connectionId}). ${activeConnections} active connections remaining.`);
        }
      }
      // tcp socket callbacks
      socket.on('end', () => {
        debugLog(`Client deconnected (end) from session ${sessionId}`);
        displayEndOfSession();
        client.close();
      });
      socket.on('close', () => {
        debugLog(`Client deconnected (close) from session ${sessionId}`);
        displayEndOfSession();
        client.close();
      });
      socket.on('error', (err) => {
        debugLog(`Client deconnected (error) from session ${sessionId}`, err);
        displayEndOfSession();
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
        displayEndOfSession();
      });
      client.on('close', () => {
        // TODO: handle reconnect ???
        debugLog(`WS Client closed from session ${sessionId}`);
        socket.destroy();
        clientConnected = false;
        displayEndOfSession();
      });
    });

    server.on('error', (err) => {
      console.log(`tcp tunnel client error`, err);
    });

    server.listen(localProcessPort, localProcessAddress, () => {
      console.log(color(`[${sessionId}]`) + ` Local TCP tunnel listening on tcp://${localProcessAddress}:${localProcessPort} and targeting ${remoteWsUrl}`);
    });

    return server;
  }

  function startLocalUdpProxy() {

    if (options.remote.indexOf('http://') === 0) {
      console.warn(color(`[${sessionId}]`) + ` You are using an insecure connection to '${options.remote}'. Please consider using '${options.remote.replace('http://', 'https://')}' to increase tunnel security.`.red)
    }

    const server = dgram.createSocket(dgramType);
    server.on('listening', function() {
      // const address = server.address();      
      let lastRemote = null;
      let clientConnected = false;
      const clientBuffer = [];
      const remoteArgs = _.entries({
        remoteHost,
        remotePort,
        transport: 'udp'
      }).filter(e => !!e[1]).map(e => `${e[0]}=${e[1]}`).join('&');
      const wsUrl = finalUrl.indexOf('?') > -1 ? finalUrl + '&' + remoteArgs: finalUrl + '?' + remoteArgs;
      const client = new WebSocket(wsUrl, {
        agent, 
        headers
      });
      // udp socket callbacks
      server.on('end', () => {
        debugLog(`Client deconnected (end) from session ${sessionId}`);
        client.close();
      });
      server.on('close', () => {
        debugLog(`Client deconnected (close) from session ${sessionId}`);
        client.close();
      });
      server.on('error', (err) => {
        debugLog(`Client deconnected (error) from session ${sessionId}`, err);
        client.close();
      });
      server.on('message', (data, remote) => {
        lastRemote = remote; // TODO: find a better way ...
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
            server.send(payload, 0, payload.length, lastRemote.port, lastRemote.address, (err) => {
              if (err) console.log('send error', err);
            });
          }
        }
      });
      client.on('error', (error) => {
        debugLog(`WS Client error from session ${sessionId}`, error);
        clientConnected = false;
      });
      client.on('close', () => {
        // TODO: handle reconnect ???
        debugLog(`WS Client closed from session ${sessionId}`);
        clientConnected = false;
      });
    });

    server.on('error', (err) => {
      console.log(`udp tunnel client error`, err);
    });

    server.bind(localProcessPort, localProcessAddress, () => {
      console.log(color(`[${sessionId}]`) + ` Local UDP tunnel listening on udp://${localProcessAddress}:${localProcessPort} and targeting ${remoteWsUrl}`);
    });

    return server;
  }

  function start() {

    const host = options.host;
    if (host) {
      headers['Host'] = host;
    }

    if (access_type === 'apikey') {
      if (!apikey) {
        if (optionalConfigFile.apikeys && options.apikeyRef && optionalConfigFile.apikeys[options.apikeyRef]) {
          apikey = optionalConfigFile.apikeys[options.apikeyRef];
        } else {
          throw new Error(color(`[${sessionId}]`) + ` No apikey specified !`);
        }
      }
      if (apikey.indexOf(":") > -1) {
        headers['Authorization'] = `Basic ${Buffer.from(apikey).toString('base64')}`;
      } else {
        headers[simpleApikeyHeaderName] = apikey;
      }
      const checker = ApiKeyAuthChecker(remoteUrl, headers);
      return checker.check().then(() => {
        console.log(color(`[${sessionId}]`) + ` Will use apikey authentication to access the service. Apikey access was successful !`.green);
        const server = startLocalProxy();
        checker.every(checkEvery, () => {
          console.log(color(`[${sessionId}]`) + ` Cannot access service with apikey anymore. Stopping the tunnel !`.red);
          server.close();
        });
        return server;
      }, text => {
        console.log(color(`[${sessionId}]`) + ` Cannot access service with apikey. An error occurred`.red, text);
      });
    } else if (access_type === 'session') {

      function startLocalProxyAndCheckSession(sessionId, remoteUrl, token, success) {
        existingSessionTokens[token] = moment().format('YYYY-MM-DD HH:mm:ss.SSS');
        const checker = SessionAuthChecker(remoteUrl, token, headers);
        finalUrl = finalUrl + '/?pappsToken=' + token;
        checker.check().then(() => {
          console.log(color(`[${sessionId}]`) + ` Will use session authentication to access the service. Session access was successful !`.green);
          const server = startLocalProxy();
          success(server);
          checker.every(checkEvery, () => {
            console.log(color(`[${sessionId}]`) + ` Cannot access service with session anymore. Stopping the tunnel !`.red);
            delete existingSessionTokens[token];
            server.close();
            awaitingReconnections.push(() => {
              return ProxyServer(options).start();
            });
          });
        }, text => {
          console.log(color(`[${sessionId}]`) + ` Cannot access service with session. An error occurred`.red, text);
        });
      }

      return tryExistingTokenBeforeRelogin(sessionId, remoteUrl).then(existingToken => {
        if (existingToken) {
          return new Promise(success => {
            startLocalProxyAndCheckSession(sessionId, remoteUrl, existingToken, success);
          });
        } else {
          if (runningInDocker) {
            console.log(color(`[${sessionId}]`) + ` Please open the following URL in your browser and log in if needed\n\n${remoteUrl}/?redirect=urn:ietf:wg:oauth:2.0:oob\n\n`);
            return new Promise(success => {
              askForToken(sessionId, color, token => {
                startLocalProxyAndCheckSession(sessionId, remoteUrl, token, success)
              });
            });
          } else {
            return open(`${remoteUrl}/?redirect=urn:ietf:wg:oauth:2.0:oob`).then(ok => {
              return new Promise(success => {
                askForToken(sessionId, color, token => {
                  startLocalProxyAndCheckSession(sessionId, remoteUrl, token, success)
                });
              });
            });
          }
        }
      });
    } else if (access_type === 'public') {
      console.log(color(`[${sessionId}]`) + ` Will use no authentication. Public access was successful !`.green);
      return new Promise(s => {
        const server = startLocalProxy();
        s(server);
      });
    } else {
      return fetch(`${remoteUrl}/.well-known/otoroshi/me`, {
        method: 'GET',
        headers: { ...headers, 'Accept': 'application/json' }
      }).then(r => {
        if (r.status === 200) {
          // access_type = public
          // console.log(color(`Automatically found "access_type" is 'public'`))
          return ProxyServer({ ...options, access_type: 'public' }).start();
        } else if (r.status === 401) {
          return r.text().then(text => {
            if (text.toLowerCase().indexOf('session') > -1) {
              // access_type = session
              // console.log(color(`Automatically found "access_type" is 'session'`))
              return ProxyServer({ ...options, access_type: 'session' }).start();
            } else if (text.toLowerCase().indexOf('api key') > -1) {
              // access_type = apikey
              // console.log(color(`Automatically found "access_type" is 'apikey'`))
              return ProxyServer({ ...options, access_type: 'apikey' }).start();
            } else {
              return Promise.reject(new Error('No legal access_type found (possible value: apikey, session, public)!'.bold.red));
            }
          })
        } else {
          return Promise.reject(new Error('No legal access_type found (possible value: apikey, session, public)!'.bold.red));
        }
      }).catch(e => {
        return Promise.reject(new Error('No legal access_type found (possible value: apikey, session, public)!'.bold.red));
      });
    }
  }

  return {
    start
  };
}

function displayHeader() {
  console.log('')
  console.log('Otoroshi TCP tunnel CLI, version 1.4.13-dev'.yellow)
  console.log('')
}

function displayHelpText() {
  console.log('Workspace usage, with a file describing the workspace');
  console.log('');
  console.log('  --config: the path of a config file containing a list of proxy settings, like a workspace or a profile. ');
  console.log('            In that case, other flag will not work except global flags.')
  console.log('')
  console.log('or one shot usage, with command line flags')
  console.log('')
  console.log('  --remote: the remote address of your service, like https://ssh.oto.tools:443');
  console.log('  --access_type: the type of access. Could be public, apikey, session');
  console.log('  --apikey: if access type is apikey, then the value of the api.');
  console.log('            The format is \'clientId:clientSecret\' or just \'clientId\'');
  console.log('')
  console.log('Optional flags with default value');
  console.log('')
  console.log('  --address: the local address on which TCP proxy is exposed. Default is 127.0.0.1');
  console.log('  --port: the local port on which TCP proxy is exposed. Default is 222');
  console.log('  --remoteHost: if you want to use dynamic targets, this will pass a remoteHost query param to Otoroshi');
  console.log('  --remotePort: if you want to use dynamic targets, this will pass a remotePort query param to Otoroshi');
  console.log('')
  console.log('Optional global flags, usable with workspace mode or one shot mode');
  console.log('')
  console.log('  --https_proxy: the address of your http proxy, if one');
  console.log('  --http_proxy: the address of your http proxy, if one');
  console.log('  --caPath: the path of your client certificate ca file, if one');
  console.log('  --certPath: the path of your client certificate file, if one');
  console.log('  --keyPath: the path of your client certificate private key file, if one');
  console.log('')
}

displayHeader();

if (!!cliOptions.h || !!cliOptions.help) {
  displayHelpText();
  process.exit(0);
}

if (cliOptions.config && fs.existsSync(cliOptions.config)) {
  const configContent = fs.readFileSync(cliOptions.config).toString('utf8');
  const configJson = JSON.parse(configContent);
  const items = (configJson.tunnels || configJson).filter(item => item.enabled);
  if (configJson.name) {
    console.log(`\nOtoroshi TCP tunnel CLI\n\n`.yellow.bold + `Launching tunnels for "${configJson.name}" configuration file located at "${cliOptions.config}"\n`.white.bold)
  }
  asyncForEach(items, item => {
    return ProxyServer(item, configJson).start().catch(e => console.log(`Error while starting proxy for ${item.name}`, e));
  });
} else {
  process.on('unhandledRejection', up => { throw up })
  ProxyServer(cliOptions).start();
}