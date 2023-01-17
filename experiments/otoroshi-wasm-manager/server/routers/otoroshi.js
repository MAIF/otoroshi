const express = require('express');
const { S3 } = require('../s3');
const { UserManager } = require("../services/user");
const { hash } = require('../utils');

const router = express.Router()

const DOMAINS = (process.env.MANAGER_ALLOWED_DOMAINS || "")
  .split(',')

// router.use((req, res, next) => {
//   if (
//     DOMAINS.includes(req.headers.host) &&
//     req.headers["otoroshi_client_id"] === process.env.OTOROSHI_CLIENT_ID,
//     req.headers["otoroshi_client_secret"] === process.env.OTOROSHI_CLIENT_SECRET
//   ) {
//     res.header('Access-Control-Allow-Origin', req.headers.origin)
//     res.header('Access-Control-Allow-Credentials', true)
//     next()
//   } else {
//     res
//       .status(403)
//       .json({
//         error: 'forbidden access'
//       })
//   }
// })

router.get('/plugins/:id', (req, res) => {
  console.log(req.params, req.user)

  const user = req.user ? req.user.email : 'admin@otoroshi.io'
  const wasmFile = `${hash(`${user}-${req.params.id}`)}.wasm`

  res.json(wasmFile);
})

router.get('/plugins', (req, res) => {
  if (req.headers['kind']) {
    const reg = req.headers['kind']

    if (reg === '*') {
      UserManager.getUsers()
        .then(users => {
          if (users.length > 0) {
            Promise.all(users.map(UserManager.getUserFromString))
              .then(users => {
                res.json(users.map(user => user.plugins).flat())
              })
          } else {
            res.json([])
          }
        })
    } else {
      UserManager.getUserFromString(hash(reg))
        .then(data => res.json(data.plugins))
    }
  } else {
    UserManager.getUser(req)
      .then(data => res.json(data.plugins))
  }
});

module.exports = router;