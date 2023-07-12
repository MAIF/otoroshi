const { WebSocket } = require('../services/websocket');
const manager = require('../logger');
const { S3 } = require('../s3');
const { FileSystem } = require('./file-system');

const JsCompiler = require('./compiler/javascript');
const GoCompiler = require('./compiler/go');
const rustCompiler = require('./compiler/rust');
const opaCompiler = require('./compiler/opa');

const { BuildOptions, CompilerOptions } = require('./compiler/compiler');

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

const MAX_JOBS = process.env.MANAGER_MAX_PARALLEL_JOBS || 2;

const loop = () => {
  log.info(`Running jobs: ${running} - BuildingJob size: ${queue.length}`)
  if (running < MAX_JOBS && queue.length > 0) {
    running += 1;

    const nextBuild = queue.shift()

    console.log(nextBuild)

    const compilerOptions = new CompilerOptions({
      wasmName: nextBuild.wasmName,
      entrypoint: nextBuild.metadata?.entrypoint,
      wasi: nextBuild.metadata?.wasi,
      isReleaseBuild: nextBuild.release
    });

    console.log(nextBuild.metadata?.wasi)

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
    }))
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

module.exports = {
  Queue: {
    addBuildToQueue: props => {
      queue.push(props);

      if (running <= 0)
        loop()
      else {
        WebSocket.emit(props.plugin, "QUEUE", `waiting - ${queue.length - 1} before the build start\n`)
      }
    },
    isBuildRunning: folder => FileSystem.buildFolderAlreadyExits('build', folder),
    isBinaryExists: (name, release) => {
      const { s3, Bucket } = S3.state()

      if (!release) {
        return Promise.resolve(false);
      } else {
        return new Promise(resolve => s3.getObject({
          Bucket,
          Key: `${name}.wasm`
        }, err => err && err.code === 'NoSuchKey' ? resolve(false) : resolve(true)))
      }
    }
  }
}