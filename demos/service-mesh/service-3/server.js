const express = require('express');
const fetch = require('node-fetch');
const app = express();
const port = process.env.PORT || 5432;

app.get('/api', (req, res) => {
  console.log('request on service-3')
  res.status(200).send({ emitter: 'service-3' });
});

app.listen(port, () => {
  console.log(`service-3 listening on port ${port}!`);
});