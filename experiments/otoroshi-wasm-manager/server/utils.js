const crypto = require('crypto');
const AdmZip = require("adm-zip");
const fs = require("fs-extra");
const path = require("path");

const hash = value => {
  const salt = process.env.SALT || crypto
    .randomBytes(16)
    .toString('hex');

  return crypto
    .pbkdf2Sync(value, salt, 50, 64, `sha256`)
    .toString(`hex`);
}

const unzip = (zipString, outputFolder) => {
  const zip = new AdmZip(zipString);
  const entries = zip.getEntries()

  return Promise.all(entries.map(entry => {
    const content = entry.getData().toString("utf8");

    const filePath = entry.entryName === 'Cargo.toml' ? '' : 'src';

    console.log(`write ${entry.entryName}`)
    return fs.writeFile(
      path.join(process.cwd(), 'build', outputFolder, filePath, entry.entryName),
      content
    )
  }))
}

module.exports = {
  hash,
  unzip
}