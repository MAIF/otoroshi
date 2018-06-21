const express = require('express');
const fetch = require('node-fetch');
const app = express();
const port = process.env.PORT || 5432;

app.get('/front', (req, res) => {
  // should be localhost when containers run on the same pod
  fetch('http://otoroshi-service-frontend:8080/api', {
    method: 'GET',
    headers: {
      'Host': 'service-1.foo.bar',
      'Accept': 'application/json'
    }
  }).then(r => r.json()).then(res1 => {
    // should be localhost when containers run on the same pod
    return fetch('http://otoroshi-service-frontend:8080/api', {
      method: 'GET',
      headers: {
        'Host': 'service-2.foo.bar',
        'Accept': 'application/json'
      }
    }).then(r => r.json()).then(res2 => {
      return [res1, res2];
    })
  }).then(resp => {
    res.status(200).send({ msg: 'hello', from: resp });
  });
});

app.listen(port, () => {
  console.log(`service-frontend listening on port ${port}!`);
});