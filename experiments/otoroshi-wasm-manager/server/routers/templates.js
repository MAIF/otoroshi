const express = require('express');
const path = require('path');

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
    if (['rust', 'assembly-script', 'js', 'go'].includes(type)) {
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

module.exports = router