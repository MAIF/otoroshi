const express = require('express');
const fetch = require('node-fetch');
const app = express();
const port = process.env.PORT || 5432;

app.get('/front', (req, res) => {
  console.log('request on service-frontend')
  // should be localhost when containers run on the same pod
  let service1CallDuration = 0;
  let Service2CallDuration = 0;
  let globalDuration = 0;
  const start = Date.now();
  console.log('Calling service-1 from service-frontend ...')
  fetch('http://otoroshi-service-frontend:8080/api', {
    method: 'GET',
    headers: {
      'Host': 'service-1.foo.bar',
      'Accept': 'application/json'
    }
  }).then(r => r.json()).then(res1 => {
    // should be localhost when containers run on the same pod
    service1CallDuration = Date.now() - start;
    const start2 = Date.now();
    console.log('Calling service-2 from service-frontend ...')
    return fetch('http://otoroshi-service-frontend:8080/api', {
      method: 'GET',
      headers: {
        'Host': 'service-2.foo.bar',
        'Accept': 'application/json'
      }
    }).then(r => r.json()).then(res2 => {
      Service2CallDuration = Date.now() - start2;
      return [res1, res2];
    })
  }).then(resp => {
    const globalDuration = Date.now() - start;
    res.status(200).send({ msg: 'hello', from: resp, service1CallDuration, Service2CallDuration, globalDuration });
  });
});

app.listen(port, () => {
  console.log(`service-frontend listening on port ${port}!`);
});