const express = require('express');
const argv = require('minimist')(process.argv.slice(2));
const port = argv.port;
const idx = argv.idx;

const app = express();
app.get('/', (req, res) => res.status(200).send({ headers: req.headers }));
app.listen(port, () => {
  // console.log(`Test server ${idx + 1} listening on port ${port}!`);
});
