const express = require('express');
const { getUser, updateUser } = require('../services/user');
const { hash, unzip } = require('../utils');

const { S3 } = require('../s3');
const { BuildQueue } = require('../services/BuildQueue');
const { createBuildFolder, buildPlugin } = require('../services/build');

const manager = require('../logger');
const log = manager.createLogger('plugins');

const router = express.Router()


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

router.delete('/:id', async (req, res) => {
  const state = S3.state()

  const data = await getUser(req);

  if (Object.keys(data).length > 0) {
    updateUser(req, {
      ...data,
      plugins: data.plugins.filter(f => f.filename !== req.params.id)
    })
      .then(() => {
        const user = hash(req.user ? req.user.email : 'admin@otoroshi.io')
        const pluginHash = hash(`${user}-${req.params.id}`)

        const params = {
          Bucket: state.Bucket,
          Key: `${pluginHash}.zip`
        }

        state.s3.deleteObject(params, (err, data) => {
          if (err) {
            res
              .status(err.statusCode)
              .json({
                error: err.code,
                status: err.statusCode
              })
          } else {
            res.json({
              deleted: true
            })
          }
        })
      })
  } else {
    res
      .status(401)
      .json({
        error: 'invalid credentials'
      })
  }
})

router.post('/:id/build', (req, res) => {
  const user = hash(req.user ? req.user.email : 'admin@otoroshi.io')
  const pluginHash = hash(`${user}-${req.params.id}`)

  // BuildQueue.buildIsAlreadyRunning(pluginHash)
  //   .then(exists => {
  //     if (exists) {
  //       res.json({ queue_id: folder });
  //     } else {
        createBuildFolder(pluginHash)
          .then(folder => {
            unzip(req.body, folder)
              .then(() => {
                BuildQueue.addBuildToQueue(folder, req.params.id)

                res.json({
                  queue_id: folder
                })
              })
          })
    //   }
    // })
})

module.exports = router