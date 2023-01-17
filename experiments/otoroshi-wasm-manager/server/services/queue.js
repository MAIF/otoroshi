const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs-extra');
const AdmZip = require('adm-zip');

const { IO } = require('../routers/logs');
const manager = require('../logger');
const { S3 } = require('../s3');
const { hash } = require('../utils');
const { FileSystem } = require('./file-system');
const { UserManager } = require('./user');

const log = manager.createLogger('build_queue');

const MAX_JOBS = process.env.MANAGER_MAX_PARALLEL_JOBS || 2;

const CARGO_BUILD = "cargo"
const CARGO_ARGS = 'build --release --target wasm32-unknown-unknown'.split(' ')

const queue = []
let running = 0

const addBuildToQueue = props => {
  queue.push(props);

  if (running === 0)
    loop()
  else {
    IO.emit(props.plugin, "QUEUE", `waiting - ${queue.length - 1} before the build start\n`)
  }
}
const startQueue = () => {
  setInterval(() => {
    loop()
  }, 5 * 60 * 1000)
  loop()
}

const build = ({ folder, plugin, wasmName, user, zipHash }) => {
  log.info(`[buildQueue SERVICE] Starting build ${folder}`)

  const root = process.cwd()
  const buildFolder = path.join(root, 'build', folder)
  const logsFolder = path.join(root, 'logs', folder)

  return fs.pathExists(logsFolder)
    .then(exists => exists ? Promise.resolve() : fs.mkdir(logsFolder))
    .then(() => {
      return new Promise((resolve, reject) => {
        const stdoutStream = fs.createWriteStream(path.join(logsFolder, 'stdout.log'), { flags: 'w+' })
        const stderrStream = fs.createWriteStream(path.join(logsFolder, 'stderr.log'), { flags: 'w+' })

        IO.emit(plugin, "BUILD", 'Starting build ...\n')

        const child = spawn(CARGO_BUILD, CARGO_ARGS, { cwd: buildFolder });

        child.stdout.on('data', data => {
          IO.emit(plugin, "BUILD", data)
          stdoutStream.write(data)
        });
        child.stderr.on('data', data => {
          IO.emit(plugin, "BUILD", data)
          stderrStream.write(data)
        });
        child.on('error', (error) => {
          IO.emitError(plugin, "BUILD", error)
          stderrStream.write(`${error.stack}\n`)
        });

        child.on('close', (code) => {
          if (code === 0) {
            IO.emit(plugin, "BUILD", "Build done.\n")
            try {
              const newFilename = `${hash(`${user}-${plugin}`)}.wasm`
              IO.emit(plugin, "PACKAGE", "Starting package ...\n")
              Promise.all([
                saveWasmFile(
                  plugin,
                  newFilename,
                  path.join(buildFolder, 'target', 'wasm32-unknown-unknown', 'release', `${wasmName}.wasm`)
                ),
                saveLogsFile(
                  plugin,
                  `${hash(`${user}-${plugin}-logs`)}.zip`,
                  logsFolder
                ),
                updateHashOfPlugin(user, plugin, zipHash, newFilename)])
                .then(() => {
                  IO.emit(plugin, "PACKAGE", "Informations has been updated\n")
                  FileSystem.cleanFolders(buildFolder, logsFolder)
                    .then(resolve)
                })
            } catch (err) {
              console.log(err)
              FileSystem.cleanFolders(buildFolder, logsFolder)
                .then(() => reject(code))
            }
          } else {
            FileSystem.cleanFolders(buildFolder, logsFolder)
              .then(() => reject(code))
          }
        });
      })
    })
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
            IO.emit(plugin, "PACKAGE", "WASM has been saved ...\n")
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
        IO.emit(plugin, "PACKAGE", "Logs has been saved ...\n")
        resolve()
      }
    })
  })
}

const loop = () => {
  log.info(`[buildQueue SERVICE] Running jobs: ${running} - Queue size: ${queue.length}`)
  if (running < MAX_JOBS && queue.length > 0) {
    running += 1;

    const nextBuild = queue.shift()
    build(nextBuild)
      .then(() => {
        IO.emit(nextBuild.plugin, "JOB", "You can now! use the generated wasm\n")
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
    .then(data => updateUser(userReq, {
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
  Queue: {
    startQueue,
    addBuildToQueue,
    buildIsAlreadyRunning
  }
}