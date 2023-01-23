require('dotenv').config();

const express = require('express');
const http = require('http');
const compression = require('compression');
const bodyParser = require('body-parser');
const path = require('path');
const { S3 } = require('./s3');

const pluginsRouter = require('./routers/plugins');
const templatesRouter = require('./routers/templates');
const otoroshiRouter = require('./routers/otoroshi');
const { WebSocket } = require('./services/websocket');

const manager = require('./logger');
const log = manager.createLogger('wasm-manager');

const { Security } = require('./security/middlewares');
const { BuildingJob } = require('./services/building-job');

S3.initializeS3Connection();
BuildingJob.start();

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

app.use(Security.extractUserFromQuery);

app.get('/', (_, res) => res.sendFile(path.join(__dirname, '..', 'ui/build', '/index.html')));

app.use('/otoroshi', otoroshiRouter);
app.use('/api/plugins', pluginsRouter);
app.use('/api/templates', templatesRouter);

const server = http.createServer(app);

WebSocket.createLogsWebSocket(server);

if (process.env.SECURITY_SALT === 'af73c9fd45199974ff2ba6a789027b89') {
  log.warn(`###################################`);
  log.warn('DEFAULT VALUES USAGE DETECTED !!!');
  log.warn('Your instance is running with the default salt.');
  log.warn('This salt is not secret because Otoroshi is an open source project.');
  log.warn('###################################');
}

const PORT = process.env.MANAGER_PORT || 5001;

server.listen(PORT, () => log.info(`listening on ${PORT}`));
