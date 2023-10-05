const express = require('express');
const { S3 } = require('../s3');
const { UserManager } = require("../services/user");
const { format } = require('../utils');
const { Security } = require('../security/middlewares');
const { ENV } = require('../configuration');

const router = express.Router()

const DOMAINS = (ENV.MANAGER_ALLOWED_DOMAINS || "")
  .split(',')

router.use((req, res, next) => {
  if (ENV.AUTH_MODE === 'NO_AUTH') {
    next()
  } else if (
    ENV.AUTH_MODE === 'AUTH' &&
    DOMAINS.includes(req.headers.host) &&
    Security.extractedUserOrApikey(req)
  ) {
    res.header('Access-Control-Allow-Origin', req.headers.origin)
    res.header('Access-Control-Allow-Credentials', true)
    next()
  } else {
    console.log(ENV.AUTH_MODE === 'AUTH')
    console.log(DOMAINS.includes(req.headers.host))
    console.log(Security.extractedUserOrApikey(req))
    console.log(req.headers.host)
    console.log(DOMAINS)
    res
      .status(403)
      .json({
        error: 'forbidden access'
      })
  }
})

router.get('/wasm/:pluginId/:version', (req, res) => getWasm(`${req.params.pluginId}-${req.params.version}.wasm`, res));
router.get('/wasm/:id', (req, res) => getWasm(req.params.id, res))

function getWasm(Key, res) {
  const { s3, Bucket } = S3.state()

  return new Promise(resolve => {
    s3.getObject({
      Bucket,
      Key
    })
      .promise()
      .then(data => {
        resolve({ content: data.Body });
      })
      .catch(err => {
        resolve({
          error: err.code,
          status: err.statusCode
        })
      });
  })
    .then(({ content, error, status }) => {
      if (error) {
        res.status(status).json({ error, status })
      } else {
        res.attachment(Key);
        res.send(content);
      }
    });
}

router.get('/plugins', (req, res) => {
  const reg = req.headers['kind'] || '*';

  if (reg === '*') {
    UserManager.getUsers()
      .then(users => {
        if (users.length > 0) {
          Promise.all(users.map(UserManager.getUserFromString))
            .then(pluginsByUser => {
              res.json(pluginsByUser
                .map(user => user.plugins)
                .flat())
            })
        } else {
          res.json([])
        }
      })
  } else {
    UserManager.getUserFromString(format(reg))
      .then(data => res.json(data.plugins))
  }
});

module.exports = router;