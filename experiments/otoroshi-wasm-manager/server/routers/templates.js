const express = require('express');
const path = require('path');
const { FileSystem } = require('../services/file-system');

const router = express.Router()

router.get('/', (req, res) => {
  if (!req.query) {
    res
      .status(400)
      .json({
        error: 'Missing type of project'
      })
  } else {
    const { type } = req.query;
    if (['rust', 'assembly-script', 'js', 'go', 'ts'].includes(type)) {
      res.sendFile(path.join(__dirname, '../templates', `${type}.zip`));
    } else {
      res
        .status(404)
        .json({
          error: 'No template for this type of project'
        })
    }
  }
});

router.get('/types/:type', (req, res) => {
  if (!req.params) {
    res
      .status(400)
      .json({
        error: 'Missing type of project'
      })
  } else {
    const { type } = req.params;
    if (['rust', 'ts'].includes(type)) {
      if (process.env.MANAGER_TYPES.startsWith('file://')) {
        const paths = [process.env.MANAGER_TYPES.replace('file://', ''), `types.${type}`];
        FileSystem.existsFile(...paths)
          .then(() => {
            res.download(FileSystem.pathsToPath(...paths), `types.${type}`)
          })
          .catch(err => {
            res.status(400).json({ error: err })
          })
      } else if (process.env.MANAGER_TYPES.startsWith('http')) {
        // fetch()
        // res.sendFile(path.join(__dirname, '../templates', `${type}.zip`));
      } else {
        res
          .status(400)
          .json({
            error: 'Unable to retrieve the types with provided configuration'
          })
      }
    } else {
      res
        .status(404)
        .json({
          error: 'No template for this type of project'
        })
    }
  }
});

module.exports = router