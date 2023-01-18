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
//     req.headers["Otoroshi-Client-Id"] === process.env.OTOROSHI_CLIENT_ID,
//     req.headers["Otoroshi-Client-Secret"] === process.env.OTOROSHI_CLIENT_SECRET
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
  const { s3, Bucket } = S3.state()

  s3.getObject({
    Bucket,
    Key: req.params.id
  })
    .promise()
    .then(data => {
      res.attachment(req.params.id);
      res.send(data.Body);
    })
    .catch(err => {
      console.log(err)
      res
        .status(err.statusCode)
        .json({
          error: err.code,
          status: err.statusCode
        })
    })
})

router.get('/plugins', (req, res) => {
  if (req.headers['kind']) {
    const reg = req.headers['kind']

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
      UserManager.getUserFromString(hash(reg))
        .then(data => res.json(data.plugins))
    }
  } else {
    UserManager.getUser(req)
      .then(data => res.json(data.plugins))
  }
});

module.exports = router;