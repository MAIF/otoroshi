const fetch = require('node-fetch');
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
    if (['rust', 'assembly-script', 'js', 'go', 'ts', 'opa'].includes(type)) {
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

router.get('/wapm', (_, res) => {
  res.sendFile(path.join(__dirname, '../templates', 'wapm.toml'))
});

router.get('/host-functions/:type', (req, res) => {
  if (!req.params.type) {
    res
      .status(400)
      .json({
        error: 'Missing type of project'
      })
  } else {
    const { type } = req.params;
    if (['go'].includes(type)) {
      if (process.env.MANAGER_TYPES.startsWith('file://')) {
        const paths = [process.env.MANAGER_TYPES.replace('file://', ''), `host-functions.${type}`];
        FileSystem.existsFile(...paths)
          .then(() => {
            res.download(FileSystem.pathsToPath(...paths), `host-functions.${type}`)
          })
          .catch(err => {
            res.status(400).json({ error: err })
          })
      } else if (process.env.MANAGER_TYPES.startsWith('http')) {
        fetch(`${process.env.MANAGER_TYPES}/host-functions.${type}`, {
          redirect: 'follow'
        })
          .then(r => r.json())
          .then(r => {
            fetch(r.download_url)
              .then(raw => raw.body.pipe(res))
          })
      } else {
        res
          .status(400)
          .json({
            error: 'Unable to retrieve the types with provided configuration'
          })
      }
    } else if (['rust'].includes(type)) {
      if (process.env.MANAGER_TYPES.startsWith('file://')) {
        const paths = [process.env.MANAGER_TYPES.replace('file://', ''), `host.rs`];
        FileSystem.existsFile(...paths)
          .then(() => {
            res.download(FileSystem.pathsToPath(...paths), `host.rs`)
          })
          .catch(err => {
            res.status(400).json({ error: err })
          })
      } else if (process.env.MANAGER_TYPES.startsWith('http')) {
        fetch(`${process.env.MANAGER_TYPES}/host.rs`, {
          redirect: 'follow'
        })
          .then(r => r.json())
          .then(r => {
            fetch(r.download_url)
              .then(raw => raw.body.pipe(res))
          })
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
          error: 'No host functions for this type of project'
        })
    }
  }
});

router.get('/types/:type', (req, res) => {
  if (!req.params.type) {
    res
      .status(400)
      .json({
        error: 'Missing type of project'
      })
  } else {
    const type = req.params.type === "rust" ? "rs" : req.params.type;
    if (['rs', 'ts'].includes(type)) {
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
        fetch(`${process.env.MANAGER_TYPES}/types.${type}`, {
          redirect: 'follow'
        })
          .then(r => r.json())
          .then(r => {
            fetch(r.download_url)
              .then(raw => raw.body.pipe(res))
          })
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