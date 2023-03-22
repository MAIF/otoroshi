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

const unzipTo = (zipString, outputPaths) => {
  const zip = new AdmZip(zipString);
  const entries = zip.getEntries();

  const folder = path.join(...outputPaths);
  return fs.mkdir(folder)
    .then(() => {
      return Promise.all(entries.map(entry => {
        try {
          const content = pako.inflateRaw(entry.getCompressedData(), { to: 'string' });

          return fs.writeFile(
            path.join(...outputPaths, entry.entryName),
            content
          )
        } catch (err) {
          console.log(err)
          return Promise.reject(err)
        }
      }))
        .then(() => folder)
    })
}

const INFORMATIONS_FILENAME = {
  go: "go.mod",
  rust: "Cargo.toml",
  js: "package.json",
  ts: "package.json",
  opa: "package.json"
};

module.exports = {
  format,
  unzip,
  unzipTo,
  INFORMATIONS_FILENAME
}