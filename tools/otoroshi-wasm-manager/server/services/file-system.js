const fs = require('fs-extra');
const path = require('path');
const { INFORMATIONS_FILENAME } = require('../utils');

const createBuildFolder = (type, name) => {
  if (['rust', 'js', 'ts'].includes(type)) {
    return new Promise(resolve => {
      fs.copy(
        path.join(process.cwd(), 'templates', 'builds', type),
        path.join(process.cwd(), 'build', name),
        err => {
          if (err) {
            console.log('An error occured while copying the folder.')
            throw err
          }
          resolve(name)
        }
      )
    })
  } else {
    return fs
      .mkdir(path.join(process.cwd(), 'build', name))
      .then(() => name)
  }
}

const cleanFolders = (...folders) => {
  return Promise.all(folders.map(folder => {
    try {
      return fs.remove(folder)
    } catch (err) {
      return Promise.resolve(err)
    }
  }))
}

const removeFolder = (...paths) => fs.remove(path.join(process.cwd(), ...paths));

const createFolderAtPath = (path) => fs.createWriteStream(path, { flags: 'w+' });

const buildFolderAlreadyExits = (...paths) => fs.pathExists(path.join(process.cwd(), ...paths));

const folderAlreadyExits = (...paths) => fs.pathExists(path.join(...paths));

const cleanBuildsAndLogsFolders = async () => {
  return Promise.all([
    path.join(process.cwd(), "build"),
    path.join(process.cwd(), "logs"),
  ].map((folder, i) => {
    return fs.readdir(folder, (_, files) => {
      const deletedFiles = (files || []).filter(file => !file.startsWith('.'));

      return cleanFolders(...deletedFiles.map(file => path.join(process.cwd(), i === 0 ? "build" : "logs", file)));
    });
  }))
}

const checkIfInformationsFileExists = (folder, pluginType) => {
  return existsFile('build', folder, INFORMATIONS_FILENAME[pluginType]);
}

const existsFile = (...paths) => {
  return new Promise((resolve, reject) => {
    fs.stat(path.join(process.cwd(), ...paths), function (err, stat) {
      if (err == null) {
        resolve();
      } else if (err.Code === 'ENOENT') {
        reject("file does not exist");
      } else {
        reject(err);
      }
    });
  });
}

const pathsToPath = (...paths) => path.join(process.cwd(), ...paths);

const writeFiles = (files, folder, isRustBuild) => {
  return Promise.all(files.map(({ name, content }) => {
    const filePath = isRustBuild ? (filePath = name === 'Cargo.toml' ? '' : 'src') : '';

    return fs.writeFile(
      path.join(process.cwd(), 'build', folder, filePath, name),
      content
    )
  }));
}

const storeWasm = (fromFolder, filename) => fs.move(fromFolder, pathsToPath(`/wasm/${filename}`));

const getLocalWasm = (id, res) => {
  fs.readFile(path.join(process.cwd(), "wasm", id))
    .then(file => res.send(file))
    .catch(() => {
      res.status(404)
        .json({ error: "file not found" });
    })
}

module.exports = {
  FileSystem: {
    writeFiles,
    createBuildFolder,
    createFolderAtPath,
    cleanFolders,
    folderAlreadyExits,
    buildFolderAlreadyExits,
    removeFolder,
    cleanBuildsAndLogsFolders,
    checkIfInformationsFileExists,
    existsFile,
    pathsToPath,
    storeWasm,
    getLocalWasm
  }
}