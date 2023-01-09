require('dotenv').config()

const express = require('express');
const cors = require('cors');
const bodyParser = require('body-parser');
const path = require('path');
const { S3 } = require('./s3');
const pluginsRouter = require('./routers/plugins');
const { extractUserFromQuery } = require('./security/ExtractUserInformation');

S3.initializeS3Connection()

S3.state().s3.listObjects({
  Bucket: S3.state().Bucket
}, (err, data) => {
  if (err)
    console.log(err)
  else
    console.log(data)
})


const app = express();
app.use(bodyParser.urlencoded());
app.use(bodyParser.json())
app.use(bodyParser.text())
app.use(cors({
  origin: 'http://localhost:3000',
  credentials: true
}))
// app.use(extractUserFromQuery)

app.get('/', (req, res) => {
  console.log(req.headers)
  res.sendFile(path.join(__dirname, '/index.html'));
})

app.use('/plugins', pluginsRouter)

app.listen(5001, () => console.log('Listening ...'))
