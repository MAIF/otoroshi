const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs-extra');

const manager = require('../logger');
const log = manager.createLogger('build_queue');

const MAX_JOBS = 2;
const CARGO_BUILD = "cargo build --release --target wasm32-unknown-unknown"

const queue = []
let running = 0

const addBuildToQueue = (folder, plugin) => {
  queue.push({
    folder,
    plugin
  });

  if (running === 0)
    execute()
}
const startQueue = () => {
  setInterval(() => {
    execute()
  }, 60 * 1000)
  execute()
}

const build = ({ folder, plugin }) => {
  log.info(`[buildQueue SERVICE] Starting build ${folder}`)

  const root = process.cwd()
  const buildFolder = path.join(root, 'build', folder)
  const logsFolder = path.join(root, 'logs', folder)

  return fs.mkdir(logsFolder)
    .then(() => {
      return new Promise((resolve, reject) => {
        const stdoutStream = fs.createWriteStream(path.join(logsFolder, 'stdout.log'))
        const stderrStream = fs.createWriteStream(path.join(logsFolder, 'stderr.log'))
        const errorStream = fs.createWriteStream(path.join(logsFolder, 'error.log'))

        const child = spawn(CARGO_BUILD, { cwd: buildFolder });

        child.stdout.on('data', data => stdoutStream.write(data));
        child.stderr.on('data', data => stderrStream.write(data));
        child.on('error', (error) => errorStream.write(error.message));

        child.on('close', (code) => {
          if (code === 0) {
            fs.copyFile(
              path.join(buildFolder, 'target', 'wasm32-unknown-unknown', 'release', `${plugin}.wasm`),
              path.join(root, 'wasm', `${plugin}.wasm`)
            )
              .then(resolve)
          } else {
            reject(code)
          }
        });
      })
    })
}

const execute = () => {
  log.info(`[buildQueue SERVICE] Running jobs: ${running} - Queue size: ${queue.length}`)
  if (running < MAX_JOBS && queue.length > 0) {
    running += 1;

    build(queue.shift())
      .then(() => {
        running -= 1;
      })
      .catch(err => {
        log.error(err)
        running -= 1;
      })
  }
}

const buildIsAlreadyRunning = folder => {
  return fs.pathExists(path.join(process.cwd(), 'build', folder))
}

module.exports = {
  BuildQueue: {
    startQueue,
    addBuildToQueue,
    buildIsAlreadyRunning
  }
}