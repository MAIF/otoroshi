const express = require('express');
const manager = require('../logger');
const { S3 } = require('../s3');
const { getUser } = require('../services/user');
const { hash } = require('../utils');
const router = express.Router()

const log = manager.createLogger('plugins');

router.get('/', (req, res) => {
  getUser(req)
    .then(data => {
      res.json(data.plugins || [])
    })
});

router.get('/:id', (req, res) => {
  const state = S3.state()

  const user = hash(req.user ? req.user.email : 'admin@otoroshi.io')
  const filename = hash(`${user}-${req.params.id}`)

  const params = {
    Bucket: state.Bucket,
    Key: `${filename}.zip`
  }

  state.s3
    .getObject(params)
    .promise()
    .then(data => {
      res.attachment('plugin.zip');
      res.send(data.Body);
    })
    .catch(err => {
      res
        .status(err.statusCode)
        .json({
          error: err.code,
          status: err.statusCode
        })
    })
})

router.post('/', (req, res) => {
  const state = S3.state()

  getUser(req)
    .then(data => {
      const user = hash(req.user ? req.user.email : 'admin@otoroshi.io')
      const params = {
        Bucket: state.Bucket,
        Key: `${user}.json`,
        Body: JSON.stringify({
          ...data,
          plugins: [
            ...(data.plugins || []),
            {
              filename: req.body.plugin,
              hash: `${hash(`${user}-${req.body.plugin}`)}.zip`
            }
          ]
        })
      }

      state.s3.upload(params, (err, data) => {
        if (err) {
          console.log(err)
          res
            .status(err.statusCode)
            .json({
              error: err.code,
              status: err.statusCode
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

router.put('/:id', (req, res) => {
  const state = S3.state()

  const user = hash(req.user ? req.user.email : 'admin@otoroshi.io')
  const pluginHash = hash(`${user}-${req.params.id}`)

  const params = {
    Bucket: state.Bucket,
    Key: `${pluginHash}.zip`,
    Body: req.body
  }

  state.s3.putObject(params, (err, data) => {
    if (err) {
      res
        .status(err.statusCode)
        .json({
          error: err.code,
          status: err.statusCode
        })
    } else {
      res.json({
        done: true
      })
    }
  })
})

module.exports = router