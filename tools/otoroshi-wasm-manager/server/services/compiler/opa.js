const { Compiler } = require("./compiler");


module.exports = options => new Compiler({
  name: 'OPA',
  options,
  commands: [
    options => `opa build -t wasm -e ${options.opa.entrypoint} ./policies.rego`,
    "tar -xzf bundle.tar.gz",
    options => `mv policy.wasm ${options.wasmName}.wasm`
  ]
});