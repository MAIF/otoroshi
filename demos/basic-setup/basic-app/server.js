const express = require('express');
const fetch = require('node-fetch');
const { Pool, Client } = require('pg')

const app = express();
const port = process.env.PORT || 8080;
const env = process.env.ENV || '--';
const pgHost = process.env.PG_HOST || '--'
const pgPort = parseInt(process.env.PG_PORT, 10) || '--'
const pgUser = process.env.PG_USER || '--'
const pgPassword = process.env.PG_PASSWORD || '--'
const pgDb = process.env.PG_DB || '--';

const pool = new Pool({
  user: pgUser,
  host: pgHost,
  database: pgDb,
  password: pgPassword,
  port: pgPort,
})

app.get('/*', (req, res) => {
  const tenant = req.get('BasicSetup-Tenant') || '--';
  const headers = req.headers;
  const context = {
    env,
    pgHost,
    pgPort,
    pgUser,
    pgPassword,
    pgDb,
    headers
  };
  console.log('request on BasicSetup', tenant, JSON.stringify(context, null, 2));
  pool.query('SELECT NOW()', (err, commandRes) => {
    if (req.path.startsWith("/api")) {
      res.status(200).type('json').send({ tenant, context, commandRes, err });
    } else {
      res.status(200).type('html').send(`
      <html>
        <body>
          <h1>Tenant: ${tenant}</h1>
          <pre>${JSON.stringify(commandRes, null, 2)}</pre>
          <pre>${JSON.stringify(context, null, 2)}</pre>
        </body>
      </html>
      `);
    }
  });
});

app.listen(port, () => {
  console.log(`BasicSetup-${env} listening on port ${port}!`);
});