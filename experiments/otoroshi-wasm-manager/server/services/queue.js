const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs-extra');

const { updateHashOfPlugin } = require('../services/user');
const { IO } = require('../routers/logs');

const manager = require('../logger');
const { S3 } = require('../s3');
const { hash } = require('../utils');
const AdmZip = require('adm-zip');
const log = manager.createLogger('build_queue');


const MAX_JOBS = 2;
const CARGO_BUILD = "cargo"
const CARGO_ARGS = 'build --release --target wasm32-unknown-unknown'.split(' ')

const queue = []
let running = 0

const addBuildToQueue = props => {
  queue.push(props);

  if (running === 0)
    execute()
}
const startQueue = () => {
  setInterval(() => {
    execute()
  }, 5 * 60 * 1000)
  execute()
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

        const child = spawn(CARGO_BUILD, CARGO_ARGS, { cwd: buildFolder });

        child.stdout.on('data', data => {
          IO.emit(plugin, data)
          stdoutStream.write(data)
        });
        child.stderr.on('data', data => {
          IO.emit(plugin, data)
          stderrStream.write(data)
        });
        child.on('error', (error) => {
          IO.emit(plugin, data)
          stderrStream.write(`${error.stack}\n`)
        });

        child.on('close', (code) => {
          if (code === 0) {
            IO.emit(plugin, `build endded`)
            try {
              const newFilename = `${hash(`${user}-${plugin}`)}.wasm`
              Promise.all([
                saveWasmFile(
                  newFilename,
                  path.join(buildFolder, 'target', 'wasm32-unknown-unknown', 'release', `${wasmName}.wasm`)
                ),
                saveLogsFile(
                  `${hash(`${user}-${plugin}-logs`)}.zip`,
                  logsFolder
                ),
                updateHashOfPlugin(user, plugin, zipHash, newFilename)])
                .then(() => {
                  cleanBuild(buildFolder, logsFolder)
                    .then(resolve)
                })
            } catch (err) {
              console.log(err)
              cleanBuild(buildFolder, logsFolder)
                .then(() => reject(code))
            }
          } else {
            cleanBuild(buildFolder, logsFolder)
              .then(() => reject(code))
          }
        });
      })
    })
}

const saveWasmFile = (filename, srcFile) => {
  const state = S3.state()

  return new Promise((resolve, reject) => {
    fs.readFile(srcFile, (err, data) => {
      if (err) {
        reject(err)
      } else {
        const params = {
          Bucket: state.Bucket,
          Key: filename,
          Body: data
        }

        state.s3.upload(params, (err, data) => {
          if (err) {
            reject(err)
          }
          else {
            resolve()
          }
        })
      }
    })
  })
}

const saveLogsFile = (filename, logsFolder) => {
  const state = S3.state()

  const zip = new AdmZip()
  zip.addLocalFolder(logsFolder, 'logs')

  const params = {
    Bucket: state.Bucket,
    Key: filename,
    Body: zip.toBuffer()
  }

  return new Promise((resolve, reject) => {
    state.s3.upload(params, err => err ? reject(err) : resolve())
  })
}

const cleanBuild = (buildFolder, logsFolder) => {
  return Promise.all([
    fs.remove(buildFolder),
    fs.remove(logsFolder)
  ])
}

const execute = () => {
  log.info(`[buildQueue SERVICE] Running jobs: ${running} - Queue size: ${queue.length}`)
  if (running < MAX_JOBS && queue.length > 0) {
    running += 1;

    build(queue.shift())
      .then(() => {
        running -= 1;
        execute()
      })
      .catch(err => {
        log.error(err)
        running -= 1;
        execute()
      })
  }
}

const buildIsAlreadyRunning = folder => {
  return fs.pathExists(path.join(process.cwd(), 'build', folder))
}

module.exports = {
  Queue: {
    startQueue,
    addBuildToQueue,
    buildIsAlreadyRunning
  }
}