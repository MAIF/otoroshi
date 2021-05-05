const express = require('express');
const jwt = require('jsonwebtoken');

const tokenKey = process.env.TOKEN_KEY || 'secret';

function OtoroshiMiddleware(opts = {}) {
  return (req, res, next) => {
    const state = req.get("Otoroshi-State") || "none";
    const v2 = state.indexOf("eyJ") === 0 && state.indexOf(".") > -1;
    if (v2) {
      jwt.verify(req.get("Otoroshi-State") || 'none', tokenKey, { issuer: 'Otoroshi' }, (err, decodedState) => {
        jwt.verify(req.get("Otoroshi-Claim") || 'none', tokenKey, { issuer: 'Otoroshi' }, (err, decoded) => {
          if (err) {
            res.status(500).send({ error: 'error decoding jwt', nerror_description: err.message });
          } else {
            req.challengeVersion = "V2";
            req.token = decoded;
            const ttl = 10 // by default its 30 seconds in the UI
            const now = parseInt((Date.now() / 1000).toFixed(0), 10)
            const token = { 'state-resp': decodedState.state, iat: now, nbf: now, exp: now + ttl, aud: 'Otoroshi' }
            res.set("Otoroshi-State-Resp", jwt.sign(token, tokenKey, { algorithm: 'HS512'}))
            next();
          }
        });
      });
    } else {
      res.set("Otoroshi-State-Resp", req.get("Otoroshi-State") || 'none');
      jwt.verify(req.get("Otoroshi-Claim") || 'none', tokenKey, { issuer: 'Otoroshi' }, (err, decoded) => {
        if (err) {
          res.status(500).send({ error: 'error decoding jwt', nerror_description: err.message });
        } else {
          req.challengeVersion = "V1";
          req.token = decoded;
          next();
        }
      });
    }
  }
}

const port = process.env.PORT || 8080;

const app = express();

app.use(OtoroshiMiddleware());

app.use('/', (req, res) => {
  res.status(200).send({
    challengeVersion: req.challengeVersion,
    token: req.token,
    url: req.url,
    method: req.method,
    header: req.headers
  })
});

app.use((err, req, res, next) => {
  console.log(err)
  res.status(500).type('application/json').send({ error: `server error`, root: err });
});

app.listen(port, () => {
  console.log('challenge-verifier listening on http://0.0.0.0:' + port);
});
