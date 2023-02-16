const path = require('path');
const toml = require('toml')
const fs = require('fs-extra')
const crypto = require('crypto')

const express = require('express');

const { UserManager } = require('../services/user');
const { hash, unzip } = require('../utils');

const { S3 } = require('../s3');
const { BuildingJob } = require('../services/building-job');
const { FileSystem } = require('../services/file-system');

const manager = require('../logger');
const log = manager.createLogger('plugins');

const router = express.Router()

router.get('/', (req, res) => {
  UserManager.getUser(req)
    .then(data => {
      res.json(data.plugins || [])
    })
});

router.get('/:id', (req, res) => {
  const { s3, Bucket } = S3.state()

  const user = hash(req.user.email)
  const filename = hash(`${user}-${req.params.id}`)

  const params = {
    Bucket,
    Key: `${filename}.zip`
  }

  s3
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
  UserManager.getUser(req)
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
  const { s3, Bucket } = S3.state()

  const user = hash(req.user.email)

  UserManager.createUserIfNotExists(req)
    .then(() => {
      UserManager.getUser(req)
        .then(data => {
          const pluginId = crypto.randomUUID()
          const plugins = [
            ...(data.plugins || []),
            {
              filename: req.body.plugin,
              type: req.body.type,
              pluginId: pluginId
            }
          ]
          const params = {
            Bucket,
            Key: `${user}.json`,
            Body: JSON.stringify({
              ...data,
              plugins
            })
          }

          s3.upload(params, (err, data) => {
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
              res
                .status(201)
                .json({
                  plugins
                })
            }
          })
        })
    })
    .catch(err => {
      res
        .status(400)
        .json({
          error: err.message
        })
    })
})

router.put('/:id', (req, res) => {
  const { s3, Bucket } = S3.state()

  const user = hash(req.user.email)
  const pluginHash = hash(`${user}-${req.params.id}`)

  const params = {
    Bucket,
    Key: `${pluginHash}.zip`,
    Body: req.body
  }

  s3.putObject(params, (err, data) => {
    if (err) {
      res
        .status(err.statusCode)
        .json({
          error: err.code,
          status: err.statusCode
        })
    } else {
      res
        .status(204)
        .json(null)
    }
  })
})

router.delete('/:id', async (req, res) => {
  const { s3, Bucket } = S3.state()

  const data = await UserManager.getUser(req);

  if (Object.keys(data).length > 0) {
    UserManager.updateUser(req, {
      ...data,
      plugins: data.plugins.filter(f => f.pluginId !== req.params.id)
    })
      .then(() => {
        console.log(data)
        const pluginHash = data.plugins
          .find(f => f.pluginId !== req.params.id) || {}
            .last_hash

        const params = {
          Bucket,
          Key: `${pluginHash}.zip`
        }

        s3.deleteObject(params, (err, data) => {
          if (err) {
            res
              .status(err.statusCode)
              .json({
                error: err.code,
                status: err.statusCode
              })
          } else {
            res
              .status(204)
              .json(null)
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
  const user = hash(req.user.email)
  const pluginHash = hash(`${user}-${req.params.id}`)

  const data = await UserManager.getUser(req)
  const plugin = (data.plugins || []).find(p => p.pluginId === req.params.id);
  const isRustBuild = plugin.type == 'rust'

  BuildingJob.buildIsAlreadyRunning(pluginHash)
    .then(async exists => {
      if (exists) {
        res.json({ queue_id: pluginHash, alreadyExists: true });
      } else {
        const folder = await FileSystem.createBuildFolder(plugin.type, pluginHash)
        await unzip(isRustBuild, req.body, folder)
        try {
          const zipHash = crypto
            .createHash('md5')
            .update(req.body.toString())
            .digest('hex')

          if (plugin['last_hash'] !== zipHash) {
            log.info(`different: ${zipHash} - ${plugin['last_hash']}`)

            const data = await fs.readFile(path.join(process.cwd(), 'build', folder, isRustBuild ? 'Cargo.toml' : 'package.json'))
            const file = isRustBuild ? toml.parse(data) : JSON.parse(data)

            BuildingJob.addBuildToQueue({
              folder,
              plugin: req.params.id,
              wasmName: isRustBuild ? file.package.name.replace('-', '_') : file.name.replace(' ', '_'),
              user: req.user ? req.user.email : 'admin@otoroshi.io',
              zipHash,
              isRustBuild
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

router.patch('/:id/filename', (req, res) => {

  UserManager.getUser(req)
    .then(data => UserManager.updateUser(req, {
      ...data,
      plugins: (data.plugins || []).map(plugin => {
        if (plugin.pluginId === req.params.id) {
          return {
            ...plugin,
            filename: req.body.filename
          }
        } else {
          return plugin
        }
      })
    }))
    .then(() => {
      res
        .status(204)
        .json(null)
    })
})

module.exports = router
