const crypto = require('crypto')
const fetch = require('node-fetch');
const express = require('express');

const { UserManager } = require('../services/user');
const { format, unzip } = require('../utils');

const { S3 } = require('../s3');
const { BuildingJob } = require('../services/building-job');
const { FileSystem } = require('../services/file-system');

const manager = require('../logger');
const { InformationsReader } = require('../services/informationsReader');
const { WebSocket } = require('../services/websocket');
const { Publisher } = require('../services/publish-job');
const log = manager.createLogger('plugins');

const router = express.Router()

router.post('/github', (req, res) => {
  const { owner, repo, ref, private } = req.body;

  fetch(`https://api.github.com/repos/${owner}/${repo}/zipball/${ref || "main"}`, {
    redirect: 'follow',
    headers: private ? {
      Authorization: `Bearer ${process.env.GITHUB_PERSONAL_TOKEN}`
    } : {}
  })
    .then(r => {
      const contentType = r.headers.get('Content-Type');
      const contentLength = r.headers.get('Content-Length');
      if (contentLength > process.env.GITHUB_MAX_REPO_SIZE) {
        return {
          status: 400,
          result: 'this repo exceed the limit of the manager'
        }
      } else if (contentType === 'application/zip') {
        r.headers.forEach((v, n) => res.setHeader(n, v));
        r.body.pipe(res);
      } else if (contentType === "application/json") {
        return r.json()
          .then(result => res.status(r.status).json(result));
      } else {
        return r.text()
          .then(result => res.status(r.status).json({ message: result }));
      }
    })
});

router.get('/', (req, res) => {
  UserManager.getUser(req)
    .then(data => {
      res.json(data.plugins || [])
    })
});

router.get('/:id', (req, res) => {
  const { s3, Bucket } = S3.state()

  const filename = req.params.id;

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
        Key: `${plugin.pluginId}-logs.zip`
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

router.post('/github/repo', (req, res) => {
  fetch(`https://api.github.com/repos/${req.body.owner}/${req.body.repo}/branches/${req.body.ref || "main"}`, {
    redirect: 'follow',
    headers: req.body.private ? {
      Authorization: `Bearer ${process.env.GITHUB_PERSONAL_TOKEN}`
    } : {}
  })
    .then(r => {
      if (r.status === 200) {
        return createPluginFromGithub(req);
      } else {
        if ((r.headers.get('Content-Type') === "application/json")) {
          return r.json()
            .then(result => ({ result, status: r.status }))
        } else {
          return r.text()
            .then(result => ({ result, status: r.status }))
        }
      }
    })
    .then(({ status, result }) => {
      res
        .status(status)
        .json({
          result, status
        })
    })
})

function createPluginFromGithub(req) {
  const { s3, Bucket } = S3.state()

  const user = format(req.user.email)

  return new Promise(resolve => {
    UserManager.createUserIfNotExists(req)
      .then(() => UserManager.getUser(req))
      .then(data => {
        const pluginId = crypto.randomUUID()
        const plugins = [
          ...(data.plugins || []),
          {
            filename: req.body.repo,
            owner: req.body.owner,
            ref: req.body.ref,
            type: 'github',
            pluginId: pluginId,
            private: req.body.private
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

        // create and add new plugin to the user
        s3.upload(params, err => {
          if (err) {
            resolve({
              status: err.statusCode,
              result: err.code
            });
          }
          else {
            resolve({ status: 201 })
          }
        });
      });
  })
    .catch(err => {
      resolve({
        status: 404,
        result: err.message
      })
    });
}

router.post('/', (req, res) => {
  const { s3, Bucket } = S3.state()

  const user = format(req.user.email)

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
  const { s3, Bucket } = S3.state();

  const params = {
    Bucket,
    Key: `${req.params.id}.zip`,
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
  const pluginId = req.params.id;

  const data = await UserManager.getUser(req)
  let plugin = (data.plugins || []).find(p => p.pluginId === pluginId);
  if (plugin.type === 'github') {
    plugin.type = req.query.plugin_type;
  }

  const isRustBuild = plugin.type == 'rust';

  BuildingJob.buildIsAlreadyRunning(pluginId)
    .then(async exists => {
      if (exists) {
        res.json({ queue_id: pluginId, alreadyExists: true });
      } else {
        const folder = await FileSystem.createBuildFolder(plugin.type, pluginId);
        await unzip(isRustBuild, req.body, folder);
        try {
          const zipHash = crypto
            .createHash('md5')
            .update(req.body.toString())
            .digest('hex');

          if (plugin['last_hash'] !== zipHash) {
            log.info(`different: ${zipHash} - ${plugin['last_hash']}`);

            FileSystem.checkIfInformationsFileExists(folder, plugin.type)
              .then(() => InformationsReader.extractInformations(folder, plugin.type))
              .then(({ pluginName, pluginVersion, err }) => {
                if (err) {
                  WebSocket.emitError(plugin.pluginId, "BUILD", err);
                  FileSystem.removeFolder('build', folder)
                    .then(() => {
                      res
                        .status(400)
                        .json({
                          error: err
                        });
                    });
                } else {
                  (plugin.type === 'opa' ? InformationsReader.extractOPAInformations(folder) : Promise.resolve({}))
                    .then(metadata => {
                      BuildingJob.addBuildToQueue({
                        folder,
                        plugin: pluginId,
                        wasmName: `${pluginName}-${pluginVersion}`,
                        user: req.user ? req.user.email : 'admin@otoroshi.io',
                        zipHash,
                        isRustBuild,
                        pluginType: plugin.type,
                        metadata
                      });

                      res.json({
                        queue_id: folder
                      });
                    })
                    .catch(err => {
                      WebSocket.emitError(plugin.pluginId, "BUILD", err)
                      res
                        .status(400)
                        .json({
                          error: err
                        })
                    })
                }
              })
              .catch(err => {
                WebSocket.emitError(plugin.pluginId, "BUILD", err);
                FileSystem.removeFolder('build', folder)
                  .then(() => {
                    res
                      .status(400)
                      .json({
                        error: err
                      });
                  });
              });
          } else {
            FileSystem.removeFolder('build', folder)
              .then(() => {
                res.json({
                  message: 'no changes found'
                })
              })
          }
        } catch (err) {
          FileSystem.removeFolder('build', folder)
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

router.post('/:id/publish', (req, res) => {
  if (!process.env.WAPM_REGISTRY_TOKEN) {
    res.status(400)
      .json({
        error: 'WAPM registry is not configured!'
      })
  } else {
    const pluginId = req.params.id;

    Publisher.publishIsAlreadyRunning(pluginId)
      .then(exists => {
        if (exists) {
          res.json({
            queue_id: pluginId,
            alreadyExists: true
          });
        } else {
          const { s3, Bucket } = S3.state();
          s3
            .getObject({
              Bucket,
              Key: `${pluginId}.zip`
            })
            .promise()
            .then(data => {
              Publisher.addPluginToQueue({
                plugin: pluginId,
                zipString: data.Body
              })
              res.json({
                queue_id: pluginId
              })
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
        }
      })
  }
});

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
