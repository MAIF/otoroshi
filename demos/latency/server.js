const express = require('express');

const port = process.env.PORT || 8080;

const app = express();

app.use('/', (req, res) => {
  const waitForStr = req.query.await || "2000";
  const waitFor = parseInt(waitForStr, 10);
  setTimeout(() => {
    res.status(200).send({
      url: req.url,
      method: req.method,
      header: req.headers
    })
  }, waitFor);
});

app.use((err, req, res, next) => {
  console.log(err)
  res.status(500).type('application/json').send({ error: `server error`, root: err });
});

app.listen(port, () => {
  console.log('latency-inducer listening on http://0.0.0.0:' + port);
});
