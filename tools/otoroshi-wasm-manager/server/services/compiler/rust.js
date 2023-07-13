const { Compiler } = require("./compiler");
const path = require('path');

function removeAfterLastHyphen(str) {
  return str.substring(0, str.lastIndexOf('-'));
}

function outputWasmFolder(buildOptions) {
  const targetFolder = buildOptions.wasi ? 'wasm32-wasi' : 'wasm32-unknown-unknown';

  const mode = buildOptions.isReleaseBuild ? 'release' : 'debug';

  const basePath = path.join(buildOptions.buildFolder, 'target', targetFolder, mode);

  const formattedWasmName = removeAfterLastHyphen(!buildOptions.isReleaseBuild ?
    this.options.wasmName.replace(new RegExp('-dev' + '$'), '') :
    this.options.wasmName);

  return path.join(basePath, `${formattedWasmName}.wasm`)
}

module.exports = options => new Compiler({
  name: 'RUST',
  options,
  commands: [
    `cargo build --manifest-path ./Cargo.toml ${options.isReleaseBuild ? '--release ' : ''}--target ${options.wasi ? "wasm32-wasi" : "wasm32-unknown-unknown"}`
  ],
  outputWasmFolder
});