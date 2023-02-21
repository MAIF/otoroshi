const AdmZip = require("adm-zip");
const fs = require("fs-extra");
const path = require("path");
const pako = require('pako');

const format = value => value.replace(/[^a-zA-Z ]/g, "");

const unzip = (isRustBuild, zipString, outputFolder) => {
  const zip = new AdmZip(zipString);
  const entries = zip.getEntries()

  return Promise.all(entries.map(entry => {
    try {
      const content = pako.inflateRaw(entry.getCompressedData(), { to: 'string' });

      let filePath = '';

      if (isRustBuild) {
        filePath = entry.entryName === 'Cargo.toml' ? '' : 'src';
      }

      return fs.writeFile(
        path.join(process.cwd(), 'build', outputFolder, filePath, entry.entryName),
        content
      )
    } catch (err) {
      return Promise.reject(err)
    }
  }))
}

module.exports = {
  format,
  unzip
}