const express = require('express');
const { S3 } = require('../s3');
const { getUser } = require("../services/user");
const { hash } = require('../utils');

const router = express.Router()

const DOMAINS = (process.env.ALLOWED_DOMAINS || "")
  .split(',')

router.use((req, res, next) => {
  if (
    DOMAINS.includes(req.headers.host) &&
    req.headers["otoroshi_client_id"] === process.env.OTOROSHI_CLIENT_ID,
    req.headers["otoroshi_client_secret"] === process.env.OTOROSHI_CLIENT_SECRET
  ) {
    res.header('Access-Control-Allow-Origin', req.headers.origin)
    res.header('Access-Control-Allow-Credentials', true)
    next()
  } else {
    res
      .status(403)
      .json({
        error: 'forbidden access'
      })
  }
})

router.get('/plugins/:id/signed-url', (req, res) => {
  const { s3, Bucket } = S3.state();

  console.log(req.params, req.user)

  const user = req.user ? req.user.email : 'admin@otoroshi.io'
  const wasmFile = `${hash(`${user}-${req.params.id}`)}.wasm`

  console.log('search', wasmFile)

  res.json(s3.getSignedUrl('getObject', {
    Bucket,
    Key: wasmFile,
    Expires: 16 * 60 * 1000
  }));
})

router.get('/plugins', (req, res) => {
  getUser(req)
    .then(data => res.json(data.plugins))
});

module.exports = router;