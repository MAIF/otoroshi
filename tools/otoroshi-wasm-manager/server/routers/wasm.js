const express = require('express');
const { S3 } = require('../s3');
const { UserManager } = require('../services/user');
const { ENV } = require('../configuration');
const { getWasm } = require('../services/wasm-s3');
const { GetObjectCommand } = require('@aws-sdk/client-s3');
const fetch = require('node-fetch');

const router = express.Router()

router.get('/runtime', (_, res) => res.json(ENV.EXTISM_RUNTIME_ENVIRONMENT === 'true'));

router.get('/:id', (req, res) => getWasm(`${req.params.id}.wasm`, res));

router.post('/:pluginId', (req, res) => {
  if (!req.params) {
    res
      .status(404)
      .json({
        error: 'Missing plugin id'
      })
  } else {
    const { pluginId } = req.params;

    UserManager.getUser(req)
      .then(data => {
        const plugin = data.plugins.find(plugin => plugin.pluginId === pluginId);

        if (!ENV.EXTISM_RUNTIME_ENVIRONMENT) {
          res
            .status(400)
            .json({
              error: 'Runtime environment is not enabled.'
            })
        } else if (plugin) {
          run(plugin.wasm, req.body, res)
        } else {
          res
            .status(404)
            .json({
              error: 'Plugin not found'
            })
        }
      })
  }
});

function run(wasm, { input, functionName, wasi }, res) {
  const { s3, Bucket } = S3.state();

  s3.send(new GetObjectCommand({
    Bucket,
    Key: wasm
  }))
    .then(data => new fetch.Response(data.Body).buffer())
    .then(async data => {
      const { Context } = require('@extism/extism');
      const ctx = new Context();
      const plugin = ctx.plugin(data, wasi);

      const buf = await plugin.call(functionName, input)
      const output = buf.toString()
      plugin.free()
      res.json({
        data: output
      })
    })
    .catch(err => {
      console.log(err)
      res
        .status(400)
        .json({
          error: err
        })
    })
}

module.exports = router