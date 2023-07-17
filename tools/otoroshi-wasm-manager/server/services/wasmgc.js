const { exec } = require('child_process');
const fs = require('fs-extra')
function wasmgc(buildOptions, path, log) {

  let command = {
    executable: 'wasm-opt',
    args: ['-O', path, '-o', path]
  }

  return new Promise((resolve) => {
    getFileSize(path)
      .then(originalSize => {
        log(`File size before optimizing: ${originalSize} bytes`)
        log(`${command.executable} ${command.args.join(" ")}`);
        exec(`${command.executable} ${command.args.join(" ")}`, (error, stdout, stderr) => {
          if (error) {
            log(error.message, true);
            return;
          }
          if (stderr) {
            log(stderr, true);
            return;
          }

          getFileSize(path)
            .then(newSize => {
              log(`File size after optimizing: ${newSize} bytes - ${(1 - (newSize / originalSize)) * 100}%`)

              resolve();
            });
        });
      });
  });
}

function getFileSize(path) {
  return new Promise(resolve => {
    fs.stat(path, (_, stats) => {
      resolve(stats?.size || 1)
    })
  })
}

module.exports = {
  optimizeBinaryFile: wasmgc
}