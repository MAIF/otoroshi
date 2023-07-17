const toml = require('toml')
const fs = require('fs-extra')
const path = require("path");
const { INFORMATIONS_FILENAME } = require('../utils');

async function extractInformations(folder, pluginType) {
  const isGoBuild = pluginType === "go";
  const isRustBuild = pluginType === "rust";

  const data = await fs.readFile(path.join(process.cwd(), 'build', folder, INFORMATIONS_FILENAME[pluginType]));

  try {
    if (isGoBuild) {
      const informations = data
        .toString()
        .split('\n')[0]
        .replace('module ', '')
        .trim()
        .split('/');

      return {
        pluginName: informations[0] || "go-plugin",
        pluginVersion: informations[1] || "1.0.0",
      };
    } else if (isRustBuild) {
      const informations = toml.parse(data);
      return {
        pluginName: informations.package.name,
        pluginVersion: informations.package.version,
        metadata: informations.package.metadata
      };
    } else { // ts and js
      const informations = JSON.parse(data);
      return {
        pluginName: informations.name,
        pluginVersion: informations.version
      }
    }
  } catch (err) {
    return { err: JSON.stringify(err, Object.getOwnPropertyNames(err)) }
  }
}

const extractOPAInformations = folder => {
  return new Promise(async (resolve, reject) => {
    try {
      const data = JSON.parse(await fs.readFile(path.join(process.cwd(), 'build', folder, "package.json")));

      resolve({
        entrypoint: (data.metadata || {}).entrypoint
      });
    } catch (err) {
      reject(JSON.stringify(err, Object.getOwnPropertyNames(err)))
    }
  })
}

module.exports = {
  InformationsReader: {
    extractInformations,
    extractOPAInformations
  }
}