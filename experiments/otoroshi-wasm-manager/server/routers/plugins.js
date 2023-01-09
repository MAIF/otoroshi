const express = require('express');
const manager = require('../logger');
const { S3 } = require('../s3');
const { getUser } = require('../services/user');
const { userHash } = require('../utils');
const router = express.Router()

const log = manager.createLogger('plugins');

router.get('/', (req, res) => {
  getUser(req)
    .then(data => {
      console.log(data)
      res.json(data.plugins || [])
    })
});

router.post('/', (req, res) => {
  const state = S3.state()

  getUser(req)
    .then(data => {
      const user = userHash(req.user ? req.user.email : 'admin@otoroshi.io')
      const params = {
        Bucket: state.Bucket,
        Key: `${user}.json`,
        Body: JSON.stringify({
          ...data,
          plugins: [
            ...(data.plugins || []),
            {
              filename: req.body.plugin,
              hash: userHash(`${user}-${req.body.plugin}`)
            }
          ]
        })
      }

      state.s3.upload(params, (err, data) => {
        if (err) {
          console.log(err)
          res
            .status(400)
            .json({
              error: err
            })
        }
        else {
          res.json({
            location: data.Location
          })
        }
      })
    })
})

module.exports = router