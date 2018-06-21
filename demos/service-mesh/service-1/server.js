const express = require('express');
const fetch = require('node-fetch');
const app = express();
const port = process.env.PORT || 5432;

app.get('/api', (req, res) => {
  res.status(200).send({ emitter: 'service-1' });
});

app.listen(port, () => {
  console.log(`service-1 listening on port ${port}!`);
});