const path = require('path');
const toml = require('toml')
const fs = require('fs-extra')
const crypto = require('crypto')

const express = require('express');

const { getUser, updateUser } = require('../services/user');
const { hash, unzip } = require('../utils');

const { S3 } = require('../s3');
const { Queue } = require('../services/Queue');
const { createBuildFolder, buildPlugin } = require('../services/build');

const manager = require('../logger');
const log = manager.createLogger('plugins');

const router = express.Router()

router.get('/', (req, res) => {
  getUser(req)
    .then(data => {
      console.log(data)
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

router.get('/:id/configurations', (req, res) => {
  const { s3, Bucket } = S3.state()
  getUser(req)
    .then(data => {
      const user = req.user ? req.user.email : 'admin@otoroshi.io'
      const plugin = data.plugins.find(f => f.pluginId === req.params.id)

      const files = [{
        ext: 'json',
        filename: 'config',
        readOnly: true,
        content: JSON.stringify({
          ...plugin
        }, null, 4)
      }]

      s3.getObject({
        Bucket,
        Key: `${hash(`${user}-${plugin.pluginId}-logs`)}.zip`
      })
        .promise()
        .then(data => {
          res.json([
            ...files,
            {
              ext: 'zip',
              filename: 'logs',
              readOnly: true,
              content: data.Body
            }
          ])
        })
        .catch(err => {
          console.log(err)
          res.json(files)
        })


    })
})

router.post('/', (req, res) => {
  const state = S3.state()

  getUser(req)
    .then(data => {
      const user = hash(req.user ? req.user.email : 'admin@otoroshi.io')
      const pluginId = crypto.randomUUID()
      const plugins = [
        ...(data.plugins || []),
        {
          filename: req.body.plugin,
          pluginId: pluginId
        }
      ]
      const params = {
        Bucket: state.Bucket,
        Key: `${user}.json`,
        Body: JSON.stringify({
          ...data,
          plugins
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
            plugins
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
      plugins: data.plugins.filter(f => f.pluginId !== req.params.id)
    })
      .then(() => {
        console.log(data)
        const pluginHash = data.plugins
          .find(f => f.pluginId !== req.params.id) || {}
            .last_hash

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

router.post('/:id/build', async (req, res) => {
  const user = hash(req.user ? req.user.email : 'admin@otoroshi.io')
  const pluginHash = hash(`${user}-${req.params.id}`)

  const data = await getUser(req)
  const plugin = (data.plugins || []).find(p => p.pluginId === req.params.id);

  Queue.buildIsAlreadyRunning(pluginHash)
    .then(async exists => {
      if (exists) {
        res.json({ queue_id: pluginHash });
      } else {
        const folder = await createBuildFolder(pluginHash)
        await unzip(req.body, folder)
        try {
          const zipHash = crypto
            .createHash('md5')
            .update(req.body.toString())
            .digest('hex')

          const data = await fs.readFile(path.join(process.cwd(), 'build', folder, 'Cargo.toml'))
          const file = toml.parse(data)

          if (plugin['last_hash'] !== zipHash) {
            console.log(`different: ${zipHash} - ${plugin['last_hash']}`)
            Queue.addBuildToQueue({
              folder,
              plugin: req.params.id,
              wasmName: file.package.name.replace('-', '_'),
              user: req.user ? req.user.email : 'admin@otoroshi.io',
              zipHash
            })

            res.json({
              queue_id: folder
            })
          } else {
            fs.remove(path.join(process.cwd(), 'build', folder))
              .then(() => {
                res.json({
                  message: 'no changes found'
                })
              })
          }
        } catch (err) {
          fs.remove(path.join(process.cwd(), 'build', folder))
            .then(() => {
              res
                .status(400)
                .json({
                  error: 'Error reading toml file',
                  message: err.message
                })
            })
        }
      }
    })
})

module.exports = router