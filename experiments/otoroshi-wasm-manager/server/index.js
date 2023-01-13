require('dotenv').config()

const express = require('express');
const cors = require('cors');
const http = require('http');
const compression = require('compression')
const bodyParser = require('body-parser');
const path = require('path');
const { S3 } = require('./s3');

const pluginsRouter = require('./routers/plugins');
const templatesRouter = require('./routers/templates');
const uiRouter = require('./routers/ui');
const { IO } = require('./routers/logs');

const { extractUserFromQuery } = require('./security/ExtractUserInformation');
const { Queue } = require('./services/Queue');

S3.initializeS3Connection()
Queue.startQueue()

// S3.cleanBucket()
S3.listObjects()

const app = express();
app.use(compression())
app.use(bodyParser.raw({
  type: 'application/octet-stream',
  limit: '10mb'
}))
app.use(bodyParser.json())
app.use(bodyParser.urlencoded());
app.use(bodyParser.text())
// app.use(extractUserFromQuery)

app.get('/', (req, res) => {
  console.log(req.headers)
  res.sendFile(path.join(__dirname, '/index.html'));
})

app.use(cors({
  origin: [process.env.OTOROSHI_UI],
  credentials: true
}))
app.use('/ui', uiRouter)

app.use('/api/plugins', pluginsRouter)
app.use('/api/templates', templatesRouter)

const server = http.createServer(app);

IO.createLogsWebSocket(server)

server.listen(5001, () => console.log('Listening ...'))
