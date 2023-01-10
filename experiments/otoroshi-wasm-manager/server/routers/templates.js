const express = require('express');
const path = require('path');
const manager = require('../logger');

const router = express.Router()
const log = manager.createLogger('templates');

router.get('/', (_, res) => {
  res.sendFile(path.join(__dirname, '../templates', 'rust.zip'));
});

module.exports = router