const { spawn } = require('child_process');
const fs = require('fs-extra');
const toml = require('toml');

const { WebSocket } = require('../services/websocket');
const manager = require('../logger');
const { FileSystem } = require('./file-system');
const { unzipTo } = require('../utils');
const { S3 } = require('../s3');

const log = manager.createLogger('PUBLISHER');

const PUBLISH_COMMAND = process.env.DOCKER_USAGE ? '/root/.wasmer/bin/wapm' : 'wapm'
const PUBLISH_ARGS = ['publish']

const queue = []
let running = 0

const addPluginToQueue = props => {
  queue.push(props);

  if (running === 0)
    loop()
  else {
    WebSocket.emit(props.plugin, "PUBLISH QUEUE", `waiting - ${queue.length - 1} before the publish start`)
  }
}

const build = ({ plugin, zipString }) => {
  log.info(`[Publish SERVICE] Starting publish ${plugin}`)

  return unzipTo(zipString, ['/tmp', plugin])
    .then(folder => new Promise(resolve => fs.readFile(`${folder}/wapm.toml`, (err, data) => resolve({ folder, err, data }))))
    .then(({ folder, err, data }) => {
      if (err) {
        WebSocket.emitError(plugin, "PUBLISH", "wapm.toml not found.")
        onFailedProcess(folder, -1, Promise.reject);
      } else {
        return new Promise((resolve, reject) => {
          WebSocket.emit(plugin, "PUBLISH", `Running command ${PUBLISH_COMMAND} ${PUBLISH_ARGS.join(' ')} ...`)

          const source = toml.parse(data).module[0].source;

          const { s3, Bucket } = S3.state()

          s3.getObject({ Bucket, Key: source })
            .promise()
            .then(data => fs.writeFile(`${folder}/${source}`, data.Body))
            .then(err => {
              if (err) {
                throw err;
              } else {
                const child = spawn(PUBLISH_COMMAND, PUBLISH_ARGS, { cwd: folder })
                addChildListener(plugin, child);

                child.on('close', (code) => {
                  if (code === 0) {
                    onSuccessProcess(plugin, folder, resolve)
                  } else {
                    onFailedProcess(folder, code, reject)
                  }
                });
              }
            })
            .catch(err => {
              console.log(err)
              onFailedProcess(folder, { error: err.code, status: err.statusCode }, reject)
            });
        });
      }
    })
}

const addChildListener = (plugin, child) => {
  child.stdout.on('data', data => WebSocket.emit(plugin, "PUBLISH", data));
  child.stderr.on('data', data => WebSocket.emit(plugin, "PUBLISH", data));
  child.on('error', (error) => WebSocket.emitError(plugin, "PUBLISH", error));
}

const onSuccessProcess = (plugin, folder, resolve) => {
  WebSocket.emit(plugin, "PUBLISH", "Publish done")
  FileSystem.cleanFolders(folder)
    .then(resolve)
}

const onFailedProcess = (folder, errorCode, reject) => {
  FileSystem.cleanFolders(folder)
    .then(() => reject(errorCode))
}

const loop = () => {
  log.info(`[Publish queue SERVICE] Running jobs: ${running} - Publisher size: ${queue.length}`)
  if (running < 1 && queue.length > 0) {
    running += 1;

    const nextPublish = queue.shift()
    build(nextPublish)
      .then(() => {
        running -= 1;
        loop()
      })
      .catch(err => {
        log.error(err)
        running -= 1;
        loop()
      })
  }
}

const publishIsAlreadyRunning = folder => FileSystem.folderAlreadyExits('/tmp', folder)

const initialize = () => {
  if (process.env.WAPM_REGISTRY_TOKEN) {
    const child = spawn('wapm', ['login', process.env.WAPM_REGISTRY_TOKEN], { cwd: '/tmp' });
    child.stdout.on('data', data => log.info(data.toString()));
    child.stderr.on('data', data => log.error(data.toString()));
    child.on('error', (error) => log.error(error.toString()));
  }
}

module.exports = {
  Publisher: {
    addPluginToQueue,
    publishIsAlreadyRunning,
    initialize
  }
}