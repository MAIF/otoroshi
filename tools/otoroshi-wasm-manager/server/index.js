require('dotenv').config();

const express = require('express');
const http = require('http');
const compression = require('compression');
const bodyParser = require('body-parser');
const path = require('path');
const { S3 } = require('./s3');
const { ENV, STORAGE } = require('./configuration');

const pluginsRouter = require('./routers/plugins');
const templatesRouter = require('./routers/templates');
const publicRouter = require('./routers/public');
const wasmRouter = require('./routers/wasm');

const { WebSocket } = require('./services/websocket');
const { FileSystem } = require('./services/file-system');
const { Security } = require('./security/middlewares');

const manager = require('./logger');
const { Publisher } = require('./services/publish-job');
const { Cron } = require('./services/cron-job');
const log = manager.createLogger('wasm-manager');


if (ENV.AUTH_MODE === "NO_AUTH") {
  console.log("###############################################################")
  console.log("#                                                             #")
  console.log('# ⚠The manager will start without authentication configured⚠  #')
  console.log("#                                                             #")
  console.log("###############################################################")
}

function initializeStorage() {
  if (ENV.STORAGE === STORAGE.S3)
    return S3.initializeS3Connection()
      .then(() => S3.createBucketIfMissing())
  else
    return Promise.resolve()
}

function createServer() {
  const app = express();
  app.use(express.static(path.join(__dirname, '..', 'ui/build')));
  app.use(compression());
  app.use(bodyParser.raw({
    type: 'application/octet-stream',
    limit: '10mb'
  }));
  app.use(bodyParser.json());
  app.use(bodyParser.urlencoded({ extended: true }));
  app.use(bodyParser.text());

  app.use('/', Security.extractUserFromQuery);
  app.use('/api/plugins', pluginsRouter);
  app.use('/api/templates', templatesRouter);
  app.use('/api/wasm', wasmRouter);

  app.use('/', publicRouter);
  app.get('/', (_, res) => res.sendFile(path.join(__dirname, '..', 'ui/build', '/index.html')));

  return http.createServer(app);
}

if (ENV.STORAGE === STORAGE.S3 && !S3.configured()) {
  console.log("[S3 INITIALIZATION](failed): S3 Bucket is missing");
  process.exit(1);
}
// else if  manage GITHUG

initializeStorage()
  .then(error => {
    if (error) {
      throw error;
    }

    FileSystem.cleanBuildsAndLogsFolders();
    Publisher.initialize();

    Cron.initialize();

    const server = createServer();

    WebSocket.createLogsWebSocket(server);

    server.listen(ENV.PORT, () => log.info(`listening on ${ENV.PORT}`));
  })