const { Compiler } = require("./compiler");

module.exports = options => new Compiler({
  name: 'GO',
  options,
  commands: [
    "go get github.com/extism/go-pdk",
    "go get github.com/buger/jsonparser",
    "go mod tidy",
    options => `tinygo build --no-debug -o ${options.wasmName}.wasm `
  ],
  withWasi: "-target=wasi"
})