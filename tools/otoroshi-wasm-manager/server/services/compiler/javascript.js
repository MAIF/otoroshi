const { Compiler } = require("./compiler");

module.exports = options => new Compiler({
  name: 'JS|TS',
  options,
  commands: [
    "npm install",
    "node esbuild.js",
    options => `extism-js dist/index.js -o ${options.wasmName}.wasm`]
});