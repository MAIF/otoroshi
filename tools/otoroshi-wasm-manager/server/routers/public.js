const express = require('express');
const { UserManager } = require("../services/user");
const { format } = require('../utils');
const { Security } = require('../security/middlewares');
const { ENV } = require('../configuration');
const { getWasm } = require('../services/wasm-s3');

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