const fs = require('fs-extra');
const path = require('path');

const createBuildFolder = name => {
  return new Promise(resolve => {
    fs.copy(
      path.join(process.cwd(), 'templates', 'build'),
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

const folderAlreadyExits = (...paths) => fs.pathExists(path.join(process.cwd(), ...paths))

module.exports = {
  FileSystem: {
    createBuildFolder,
    cleanFolders,
    folderAlreadyExits
  }
}