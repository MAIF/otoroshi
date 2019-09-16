const fs = require('fs'); 
const net = require('net');
const fetch = require('node-fetch');
const https = require('https');
const faker = require('faker');
const HttpsProxyAgent = require('https-proxy-agent');
const WebSocket = require('ws');

const options = require('minimist')(process.argv.slice(2));
const proxy = process.env.http_proxy || options.proxy;
const debug = options.debug || false;
const remoteUrl = (options.remote || 'http://foo.oto.tools:9999').replace('http://', 'ws://').replace('https://', 'wss://');
const localProcessAddress = options.address || '127.0.0.1';
const localProcessPort = options.port || 2222;
const public = options.public;
const session = options.session;
const apikey = options.apikey;
const simpleApikeyHeaderName = options.sahn || 'x-api-key';

const clientCaPath = options.caPath;
const clientCertPath = options.certPath;
const clientKeyPath = options.keyPath;

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

const headers = {};
let finalUrl = remoteUrl + '/.well-known/otoroshi/tunnel';

function startLocalServer() {
  const server = net.createServer((socket) => {

    socket.setKeepAlive(true, 60000);

    const sessionId = faker.random.alphaNumeric(64);
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
    console.log(`Local tunnel client listening on tcp://${localProcessAddress}:${localProcessPort} and targeting ${remoteUrl}`);
  });
}

if (apikey) {
  if (apikey.indexOf(":") > -1) {
    headers['Authorization'] = `Basic ${Buffer.from(apikey).toString('base64')}`;
  } else {
    headers[simpleApikeyHeaderName] = apikey;
  }
  // TODO: if apikey, check if it works, or crash the process then
  startLocalServer();
}

if (session == "true") {
  // TODO: open in browser and wait for user input
  finalUrl = finalUrl + 'pappsToken=' + 'TODO: token from input'
  // TODO: run periodic process to kill connections and trigger reconnection
  startLocalServer();
}

if (public == "true") {
  startLocalServer();
}