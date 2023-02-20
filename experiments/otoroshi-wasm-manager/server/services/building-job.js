const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs-extra');
const AdmZip = require('adm-zip');

const { WebSocket } = require('../services/websocket');
const manager = require('../logger');
const { S3 } = require('../s3');
const { hash } = require('../utils');
const { FileSystem } = require('./file-system');
const { UserManager } = require('./user');

const log = manager.createLogger('build_queue');

const MAX_JOBS = process.env.MANAGER_MAX_PARALLEL_JOBS || 2;

const CARGO_BUILD = ["cargo"]
const CARGO_ARGS = _ => ['build --release --target wasm32-unknown-unknown']
  .map(command => command.split(' '))

const ASSEMBLY_SCRIPT_BUILD = ["npm", "npx"]
const ASSEMBLY_SCRIPT_ARGS = wasmName => ["install", `asc plugin.ts --outFile ${wasmName}.wasm --use abort=plugin/myAbort --transform json-as/transform`]
  .map(command => command.split(' '))

const queue = []
let running = 0

const addBuildToQueue = props => {
  queue.push(props);

  if (running === 0)
    loop()
  else {
    WebSocket.emit(props.plugin, "QUEUE", `waiting - ${queue.length - 1} before the build start\n`)
  }
}
const start = () => {
  setInterval(() => {
    loop()
  }, 5 * 60 * 1000)
}

const build = ({ folder, plugin, wasmName, user, zipHash, isRustBuild }) => {
  log.info(`[buildQueue SERVICE] Starting build ${folder}`)

  const root = process.cwd()
  const buildFolder = path.join(root, 'build', folder)
  const logsFolder = path.join(root, 'logs', folder)

  return fs.pathExists(logsFolder)
    .then(exists => exists ? Promise.resolve() : fs.mkdir(logsFolder))
    .then(() => {
      return new Promise((resolve, reject) => {
        const stdoutStream = FileSystem.createFolderAtPath(path.join(logsFolder, 'stdout.log'));
        const stderrStream = FileSystem.createFolderAtPath(path.join(logsFolder, 'stderr.log'));

        WebSocket.emit(plugin, "BUILD", 'Starting build ...\n')

        const commands = isRustBuild ? CARGO_BUILD : ASSEMBLY_SCRIPT_BUILD;
        const args = (isRustBuild ? CARGO_ARGS : ASSEMBLY_SCRIPT_ARGS)(wasmName);

        commands
          .reduce((promise, fn, index) => promise.then(() => {
            return new Promise(childResolve => {
              WebSocket.emit(plugin, "BUILD", `Running command ${fn} ${args[index].join(' ')} ...\n`)

              const child = spawn(fn, args[index], { cwd: buildFolder })

              addChildListener(plugin, child, stdoutStream, stderrStream)

              child.on('close', (code) => {
                if (code === 0) {
                  if (commands.length - 1 === index) {
                    onSuccessProcess(plugin, user, buildFolder, logsFolder, wasmName, zipHash, resolve, reject, code, isRustBuild);
                  } else {
                    childResolve()
                  }
                } else {
                  onFailedProcess([buildFolder, logsFolder], code, reject);
                }
              });
            })
          }), Promise.resolve())
          .then()
      })
    })
}

const addChildListener = (plugin, child, stdoutStream, stderrStream) => {
  child.stdout.on('data', data => {
    WebSocket.emit(plugin, "BUILD", data)
    stdoutStream.write(data)
  });
  child.stderr.on('data', data => {
    WebSocket.emit(plugin, "BUILD", data)
    stderrStream.write(data)
  });
  child.on('error', (error) => {
    WebSocket.emitError(plugin, "BUILD", error)
    stderrStream.write(`${error.stack}\n`)
  });
}

const onSuccessProcess = (plugin, user, buildFolder, logsFolder, wasmName, zipHash, resolve, reject, code, isRustBuild) => {
  WebSocket.emit(plugin, "BUILD", "Build done.\n")
  try {
    const newFilename = `${hash(`${user}-${plugin}`)}.wasm`
    WebSocket.emit(plugin, "PACKAGE", "Starting package ...\n")
    Promise.all([
      saveWasmFile(
        plugin,
        newFilename,
        isRustBuild ?
          path.join(buildFolder, 'target', 'wasm32-unknown-unknown', 'release', `${wasmName}.wasm`) :
          path.join(buildFolder, `${wasmName}.wasm`)
      ),
      saveLogsFile(
        plugin,
        `${hash(`${user}-${plugin}-logs`)}.zip`,
        logsFolder
      ),
      updateHashOfPlugin(user, plugin, zipHash, newFilename)
    ])
      .then(() => {
        WebSocket.emit(plugin, "PACKAGE", "Informations has been updated\n")
        FileSystem.cleanFolders(buildFolder, logsFolder)
          .then(resolve)
      })
      .catch(err => {
        log.error(`Build failed: ${err}`)
        onFailedProcess([buildFolder, logsFolder], code, reject)
      })
  } catch (err) {
    log.error(`Build failed: ${err}`)
    onFailedProcess([buildFolder, logsFolder], code, reject)
  }
}

const onFailedProcess = (folders, errorCode, reject) => {
  FileSystem.cleanFolders(...folders)
    .then(() => reject(errorCode))
}

const saveWasmFile = (plugin, filename, srcFile) => {
  const { s3, Bucket } = S3.state()

  return new Promise((resolve, reject) => {
    fs.readFile(srcFile, (err, data) => {
      if (err) {
        reject(err)
      } else {
        const params = {
          Bucket,
          Key: filename,
          Body: data
        }

        s3.upload(params, (err, data) => {
          if (err) {
            reject(err)
          }
          else {
            WebSocket.emit(plugin, "PACKAGE", "WASM has been saved ...\n")
            resolve()
          }
        })
      }
    })
  })
}

const saveLogsFile = (plugin, filename, logsFolder) => {
  const { s3, Bucket } = S3.state()

  const zip = new AdmZip()
  zip.addLocalFolder(logsFolder, 'logs')

  const params = {
    Bucket,
    Key: filename,
    Body: zip.toBuffer()
  }

  return new Promise((resolve, reject) => {
    s3.upload(params, err => {
      if (err) {
        reject(err)
      }
      else {
        WebSocket.emit(plugin, "PACKAGE", "Logs has been saved ...\n")
        resolve()
      }
    })
  })
}

const loop = () => {
  log.info(`[buildQueue SERVICE] Running jobs: ${running} - BuildingJob size: ${queue.length}`)
  if (running < MAX_JOBS && queue.length > 0) {
    running += 1;

    const nextBuild = queue.shift()
    build(nextBuild)
      .then(() => {
        WebSocket.emit(nextBuild.plugin, "JOB", "You can now! use the generated wasm\n")
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

const buildIsAlreadyRunning = folder => FileSystem.folderAlreadyExits('build', folder)

function updateHashOfPlugin(user, plugin, newHash, wasm) {
  const userReq = {
    user: {
      email: user
    }
  }

  return UserManager.getUser(userReq)
    .then(data => UserManager.updateUser(userReq, {
      ...data,
      plugins: data.plugins.map(d => {
        if (d.pluginId === plugin) {
          return {
            ...d,
            last_hash: newHash,
            wasm
          }
        } else {
          return d
        }
      })
    }))
}

module.exports = {
  BuildingJob: {
    start,
    addBuildToQueue,
    buildIsAlreadyRunning
  }
}