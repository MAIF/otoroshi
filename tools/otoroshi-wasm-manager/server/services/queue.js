const { WebSocket } = require('../services/websocket');
const manager = require('../logger');
const { S3 } = require('../s3');
const { FileSystem } = require('./file-system');

const JsCompiler = require('./compiler/javascript');
const GoCompiler = require('./compiler/go');
const rustCompiler = require('./compiler/rust');
const opaCompiler = require('./compiler/opa');

const { BuildOptions, CompilerOptions } = require('./compiler/compiler');
const { ENV } = require('../configuration');
const { GetObjectCommand, HeadObjectCommand } = require('@aws-sdk/client-s3');

const COMPILERS = {
  'js': JsCompiler,
  'ts': JsCompiler,
  'go': GoCompiler,
  'rust': rustCompiler,
  'opa': opaCompiler
};

const log = manager.createLogger('BUILDER');

let running = 0;
const queue = [];

const MAX_JOBS = ENV.MANAGER_MAX_PARALLEL_JOBS || 2;

const loop = () => {
  log.info(`Running jobs: ${running} - BuildingJob size: ${queue.length}`)
  if (running < MAX_JOBS && queue.length > 0) {
    running += 1;

    const nextBuild = queue.shift()

    const compilerOptions = new CompilerOptions({
      wasmName: nextBuild.wasmName,
      entrypoint: nextBuild.metadata?.entrypoint,
      wasi: nextBuild.metadata?.wasi,
      isReleaseBuild: nextBuild.release
    });

    const compiler = COMPILERS[nextBuild.pluginType](compilerOptions);

    compiler.build(new BuildOptions({
      folderPath: nextBuild.folder,
      pluginId: nextBuild.plugin,
      userEmail: nextBuild.user,
      pluginZipHash: nextBuild.zipHash,
      pluginType: nextBuild.pluginType,
      metadata: nextBuild.metadata,
      isReleaseBuild: nextBuild.release,
      wasi: nextBuild.metadata?.wasi,
      saveInLocal: nextBuild.saveInLocal
    }))
      .then(() => {
        WebSocket.emit(nextBuild.plugin, "JOB", "You can now use the generated wasm")
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

const addBuildToQueue = props => {
  queue.push(props);

  loop()
  // else {
  //   WebSocket.emit(props.plugin, "QUEUE", `waiting - ${queue.length} before the build start`)
  // }
}

module.exports = {
  Queue: {
    addBuildToQueue,
    isBuildRunning: folder => FileSystem.buildFolderAlreadyExits('build', folder),
    isBinaryExists: (name, release) => {
      const { s3, Bucket } = S3.state()

      if (!release) {
        return Promise.resolve(false);
      } else {
        return s3.send(new HeadObjectCommand({
          Bucket,
          Key: `${name}.wasm`
        }))
          .then(() => true)
          .catch(() => false)
      }
    }
  }
}