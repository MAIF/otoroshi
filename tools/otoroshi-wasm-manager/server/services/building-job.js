const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs-extra');
const AdmZip = require('adm-zip');

const { WebSocket } = require('../services/websocket');
const manager = require('../logger');
const { S3 } = require('../s3');
const { FileSystem } = require('./file-system');
const { UserManager } = require('./user');

const log = manager.createLogger('BUILDER');

const MAX_JOBS = process.env.MANAGER_MAX_PARALLEL_JOBS || 2;

const CARGO_BUILD = ["cargo"]
const CARGO_ARGS = _ => ['build --manifest-path ./Cargo.toml --release --target wasm32-unknown-unknown']
  //const CARGO_ARGS = _ => ['build --release --target wasm32-wasi']
  .map(command => command.split(' '));

const JS_BUILD = ["npm", "node", "extism-js"]
const JS_ARGS = wasmName => ["install", "esbuild.js", `dist/index.js -o ${wasmName}.wasm`]
  .map(command => command.split(' '));

const GO_BUILD = ["go", "go", "go", "tinygo"]
const GO_ARGS = wasmName => [
  "get github.com/extism/go-pdk",
  "get github.com/buger/jsonparser",
  "mod tidy",
  `build --no-debug -target=wasi -o ${wasmName}.wasm `
]
  .map(command => command.split(' '));

const OPA_BUILD = ["opa", "tar", "mv"]
const OPA_ARGS = (entrypoint, wasmName) => [
  `build -t wasm -e ${entrypoint} ./policies.rego`,
  '-xzf bundle.tar.gz',
  `policy.wasm ${wasmName}.wasm`
].map(command => command.split(' '));

const queue = []
let running = 0

const addBuildToQueue = props => {
  queue.push(props);

  if (running <= 0)
    loop()
  else {
    WebSocket.emit(props.plugin, "QUEUE", `waiting - ${queue.length - 1} before the build start\n`)
  }
}

const build = ({ folder, plugin, wasmName, user, zipHash, isRustBuild, pluginType, metadata, release }) => {
  log.info(`Starting build ${folder}`)

  const root = process.cwd()
  const buildFolder = path.join(root, 'build', folder)
  const logsFolder = path.join(root, 'logs', folder)

  return fs.pathExists(logsFolder)
    .then(exists => exists ? Promise.resolve() : fs.mkdir(logsFolder))
    .then(() => {
      return new Promise((resolve, reject) => {
        const stdoutStream = FileSystem.createFolderAtPath(path.join(logsFolder, 'stdout.log'));
        const stderrStream = FileSystem.createFolderAtPath(path.join(logsFolder, 'stderr.log'));

        WebSocket.emit(plugin, release, 'Starting build ...\n')

        const { commands, args } = (pluginType === 'rust' ? {
          commands: CARGO_BUILD,
          args: CARGO_ARGS()
        } : (pluginType === 'js' || pluginType === 'ts') ? {
          commands: JS_BUILD,
          args: JS_ARGS(wasmName)
        } : pluginType === 'go' ? {
          commands: GO_BUILD,
          args: GO_ARGS(wasmName)
        } : pluginType === 'opa' ? {
          commands: OPA_BUILD,
          args: OPA_ARGS(metadata.entrypoint, wasmName)
        } : {
          commands: [],
          args: []
        });

        commands
          .reduce((promise, fn, index) => promise.then(() => {
            return new Promise(childResolve => {
              WebSocket.emit(plugin, release, `Running command ${fn} ${args[index].join(' ')} ...\n`)

              const child = spawn(fn, args[index], { cwd: buildFolder })

              addChildListener(plugin, child, stdoutStream, stderrStream, release)

              child.on('close', (code) => {
                if (code === 0) {
                  if (commands.length - 1 === index) {
                    onSuccessProcess(plugin, user, buildFolder, logsFolder, wasmName, zipHash, resolve, reject, code, isRustBuild, release);
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

const addChildListener = (plugin, child, stdoutStream, stderrStream, release) => {
  child.stdout.on('data', data => {
    WebSocket.emit(plugin, release, data)
    stdoutStream.write(data)
  });
  child.stderr.on('data', data => {
    WebSocket.emit(plugin, release, data)
    stderrStream.write(data)
  });
  child.on('error', (error) => {
    WebSocket.emitError(plugin, release, error)
    stderrStream.write(`${error.stack}\n`)
  });
}

const removeAfterLastHyphen = (str) => {
  return str.substring(0, str.lastIndexOf('-'))
}

const onSuccessProcess = (plugin, user, buildFolder, logsFolder, wasmName, zipHash, resolve, reject, code, isRustBuild, release) => {
  WebSocket.emit(plugin, release, "Build done.\n")
  try {
    const newFilename = `${wasmName}.wasm`
    WebSocket.emit(plugin, "PACKAGE", "Starting package ...\n")
    Promise.all([
      saveWasmFile(
        plugin,
        newFilename,
        isRustBuild ?
          path.join(buildFolder, 'target', 'wasm32-unknown-unknown', 'release',
            (release ?
              `${removeAfterLastHyphen(wasmName)}.wasm` :
              `${removeAfterLastHyphen(removeAfterLastHyphen(wasmName))}.wasm`).replace(/-/g, "_")
          ) :
          //path.join(buildFolder, 'target', 'wasm32-wasi', 'release', `${wasmName.substring(0, wasmName.lastIndexOf('-'))}.wasm`) :
          path.join(buildFolder, `${wasmName}.wasm`)
      ),
      saveLogsFile(
        plugin,
        `${plugin}-logs.zip`,
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
        console.log(err)
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
  log.info(`Running jobs: ${running} - BuildingJob size: ${queue.length}`)
  if (running < MAX_JOBS && queue.length > 0) {
    running += 1;

    const nextBuild = queue.shift()
    build(nextBuild)
      .then(() => {
        WebSocket.emit(nextBuild.plugin, "JOB", "You can now use the generated wasm\n")
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

const buildIsAlreadyRunning = folder => FileSystem.buildFolderAlreadyExits('build', folder)

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
          let versions = d.versions || [];

          // convert legacy array
          if (versions.length > 0 && isAString(versions[0])) {
            versions = versions.map(name => ({ name }))
          }

          const index = versions.findIndex(item => item.name === wasm);
          if (index === -1)
            versions.push({
              name: wasm,
              updated_at: Date.now(),
              creator: user
            })
          else {
            versions[index] = {
              ...versions[index],
              updated_at: Date.now(),
              creator: user
            }
          }

          return {
            ...d,
            last_hash: newHash,
            wasm,
            versions
          }
        } else {
          return d
        }
      })
    }))
}

const isAString = variable => typeof variable === 'string' || variable instanceof String;

const checkIfBinaryExists = (name, release) => {
  const { s3, Bucket } = S3.state()

  if (!release) {
    return Promise.resolve(false);
  } else {
    return new Promise(resolve => {
      s3.getObject({
        Bucket,
        Key: `${name}.wasm`
      }, err => {
        if (err && err.code === 'NoSuchKey') {
          resolve(false);
        } else {
          resolve(true);
        }
      })
    })
  }
}

module.exports = {
  BuildingJob: {
    addBuildToQueue,
    buildIsAlreadyRunning,
    checkIfBinaryExists
  }
}