require('dotenv').config();

const express = require('express');
const http = require('http');
const compression = require('compression');
const bodyParser = require('body-parser');
const path = require('path');
const { S3 } = require('./s3');
const { ENV } = require('./configuration');

const pluginsRouter = require('./routers/plugins');
const templatesRouter = require('./routers/templates');
const publicRouter = require('./routers/public');
const wasmRouter = require('./routers/wasm');

const { WebSocket } = require('./services/websocket');
const { FileSystem } = require('./services/file-system');
const { Security } = require('./security/middlewares');

const manager = require('./logger');
const { Publisher } = require('./services/publish-job');
const log = manager.createLogger('wasm-manager');

S3.initializeS3Connection()
  .then(() => {
    S3.createBucketIfMissing();
    FileSystem.cleanBuildsAndLogsFolders();
    // Publisher.initialize();
    // S3.cleanBucket()
    // S3.listObjects()

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

    const server = http.createServer(app);

    WebSocket.createLogsWebSocket(server);

    const PORT = ENV.MANAGER_PORT || 5001;

    if(ENV.AUTH_MODE === "NO_AUTH") {
      console.log("###########################################################")
      console.log("###########################################################")
      console.log('The manager will start without authentication configured !!')
      console.log("###########################################################")
      console.log("###########################################################")
    }

    server.listen(PORT, () => log.info(`listening on ${PORT}`));
  })