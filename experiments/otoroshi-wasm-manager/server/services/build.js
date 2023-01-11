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


module.exports = {
  createBuildFolder
}