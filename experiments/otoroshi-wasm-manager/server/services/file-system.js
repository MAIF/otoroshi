const fs = require('fs-extra');
const path = require('path');

const createBuildFolder = (type, name) => {
  console.log('createBuildFolder', process.cwd())
  if (type === 'rust') {
    return new Promise(resolve => {
      fs.copy(
        path.join(process.cwd(), 'templates', 'builds', 'rust'),
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

const createFolderAtPath = (path) => fs.createWriteStream(path, { flags: 'w+' })

const folderAlreadyExits = (...paths) => fs.pathExists(path.join(process.cwd(), ...paths))

module.exports = {
  FileSystem: {
    createBuildFolder,
    createFolderAtPath,
    cleanFolders,
    folderAlreadyExits
  }
}