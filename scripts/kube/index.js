const express = require('express');
const bodyParsers = require('body-parser');

const app = express();

app.use(bodyParsers.json());

function handle(req, res) {
  if (req.query.watch === '1' || req.query.watch === 1) {
    console.log('watch', req.method, req.path, req.query.watch, req.query.resourceVersion, req.query.timeoutSeconds)
    res.writeHead(204, { 'Content-Type': 'application/json' });
    setTimeout(() => {
      res.end();
    }, parseInt(req.query.timeoutSeconds, 10) * 1000)
  } else {
    res.status(200).send({
      apiVersion: req.params.api || 'v1',
      kind: req.params.resource,
      metadata: {
        name: "http-app-group",
        namespace: req.params.namespace || 'default',
        resourceVersion: "1"
      },       
      spec: {
        description: "a group to hold services about the http-app"
      }
    });
  }
}

app.get('/apis/:version/:resource', handle);
app.get('/apis/:api/:version/:resource', handle);

app.all('/*', (req, res) => {
  res.status(204).send('');
})

app.listen(6443, () => {
  console.log(`fake kube listening at http://127.0.0.1:6443`)
});