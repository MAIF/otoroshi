const fs = require('fs-extra');
const path = require('path');

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

const folderAlreadyExits = (...paths) => fs.pathExists(path.join(process.cwd(), ...paths));

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

module.exports = {
  FileSystem: {
    createBuildFolder,
    createFolderAtPath,
    cleanFolders,
    folderAlreadyExits,
    removeFolder,
    cleanBuildsAndLogsFolders
  }
}