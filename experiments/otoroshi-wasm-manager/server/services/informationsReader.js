const toml = require('toml')
const fs = require('fs-extra')
const path = require("path");

const INFORMATIONS_FILENAME = {
  go: "go.mod",
  rust: "Cargo.toml",
  js: "package.json",
  ts: "package.json"
};

async function extractInformations(folder, pluginType) {
  const isGoBuild = pluginType === "go";
  const isRustBuild = pluginType === "rust";

  const data = await fs.readFile(path.join(process.cwd(), 'build', folder, INFORMATIONS_FILENAME[pluginType]));

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
      pluginName: informations.package.name.replace('-', '_'),
      pluginVersion: informations.package.version
    };
  } else { // ts and js
    const informations = JSON.parse(data);
    return {
      pluginName: informations.name.replace('-', '_'),
      pluginVersion: informations.file.version
    }
  }
}

module.exports = {
  InformationsReader: {
    extractInformations
  }
}