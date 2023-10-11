const cron = require('node-cron');
const manager = require('../logger');

const fs = require('fs-extra');
const path = require('path');
const { ENV } = require('../configuration');

const log = manager.createLogger('CRON');

const cleaningWasm = () => {
  log.info("Start cleaning wasm folder");

  const root = path.join(process.cwd(), 'wasm');

  fs.readdir(root)
    .then(files => Promise.all(files
      .filter(file => !file.includes('.gitkeep'))
      .map(file => {
        const filepath = path.join(root, file);
        return fs.stat(filepath)
          .then(data => {
            if (Date.now() - data.birthtimeMs >= ENV.LOCAL_WASM_JOB_CLEANING) {
              log.info(`Remove ${filepath}`)
              fs.unlink(filepath)
            }
          })
      })))
    .then(() => {
      log.info("End cleaning");
    })

}

const initialize = () => {
  cron.schedule('*/60 * * * *', cleaningWasm);
}

module.exports = {
  Cron: {
    initialize
  }
}