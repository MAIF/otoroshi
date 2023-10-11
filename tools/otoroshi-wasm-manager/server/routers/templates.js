const fetch = require('node-fetch');
const express = require('express');
const path = require('path');
const { FileSystem } = require('../services/file-system');
const { ENV } = require('../configuration');

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
    if (['rust', 'assembly-script', 'js', 'go', 'ts', 'opa'].includes(type)) {
      getTemplates(type, res);
    } else {
      res
        .status(404)
        .json({
          error: 'No template for this type of project'
        })
    }
  }
});

router.get('/wapm', (_, res) => {
  res.sendFile(path.join(__dirname, '../templates', 'wapm.toml'))
});

function getTemplatesFromPath(type, res) {
  return res.sendFile(path.join(__dirname, '../templates', `${type}.zip`))
}

function getTemplates(type, res) {
  const source = ENV.MANAGER_TEMPLATES;
  const zipName = `${type}.zip`;

  if (!source) {
    return getTemplatesFromPath(type, res);
  } else if (source.startsWith('file://')) {
    const paths = [source.replace('file://', ''), zipName];
    
    FileSystem.existsFile(...paths)
      .then(() => {
        res.download(FileSystem.pathsToPath(...paths), zipName)
      })
      .catch(err => {
        res
          .status(400)
          .json({ error: err })
      })
  } else if (source.startsWith('http')) {
    fetch(`${source}/${zipName}`, {
      redirect: 'follow'
    })
      .then(r => r.json())
      .then(r => {
        fetch(r.download_url)
          .then(raw => raw.body.pipe(res))
      })
  } else {
    res
      .status(404)
      .json({
        error: 'No template for this type of project'
      })
  }
}

module.exports = router