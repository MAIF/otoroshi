const express = require('express');
const bodyParser = require('body-parser');
const elasticsearch = require('elasticsearch');
const moment = require('moment');

const app = express().use(bodyParser.json());
const port = process.env.PORT || 8080;
const ES_HOST = process.env.ES_HOST || 'localhost';
const ES_PORT = process.env.ES_PORT || 9200;
const ES_PROTOCOL = process.env.ES_PROTOCOL || 'http';

const INDEX_NAME = 'analytics';
const TYPE_NAME = 'analytics';

Array.prototype.flatMap = function(lambda) {
  return Array.prototype.concat.apply([], this.map(lambda));
};

const TEMPLATE = {
  "template": "analytics-*",
  "settings": {
    "number_of_shards": 1,
    "index": {
    }
  },
  "mappings": {
    "_default_": {
      "_all": {
        "enabled": false
      },
      "dynamic_templates": [
        {
          "string_template": {
            "match": "*",
            "mapping": {
              "type": "text",
              "fields": {
                "raw": {
                  "type": "keyword"
                }
              }
            },
            "match_mapping_type": "string"
          }
        }
      ],
      "properties": {
        "@id": {
          "type": "keyword"
        },
        "@timestamp": {
          "type": "date"
        },
        "@created": {
          "type": "date"
        },
        "@product": {
          "type": "keyword"
        },
        "@type": {
          "type": "keyword"
        },
        "@service": {
          "type": "keyword"
        },
        "@env": {
          "type": "keyword"
        }
      }
    }
  }
};

const esHostConfig = {
  host: ES_HOST,
  auth: null,
  protocol: ES_PROTOCOL,
  port: ES_PORT,
};

let clientHolder = null;
function client() {
  if (!clientHolder) {
    console.log('Connecting to elastic', esHostConfig);
    const client = new elasticsearch.Client({
      host: [esHostConfig],
      log: 'info',
    });
    console.log('Setting template');
    client.indices.putTemplate(
      {
        id: 'analytics-tpl',
        body: TEMPLATE,
        create: true
      },
      (error, response) => {
        if (error) {
          console.log('Error creating template', error);
        } else {
          console.log('Template updated', response);
        }
      }
    );
    clientHolder = client;
  }
  return clientHolder;
}

app.post('/api/v1/events', (req, res) => {
  const body = req.body;
  if (!body || !Array.isArray(body)) {
    return res.sendStatus(400);
  } else {
    console.log(`Bulking ${body.length} event in ES ...`);
    client().bulk(
      {
        body: body.flatMap(event => [
          {
            index: {
              _index: INDEX_NAME + '-' + moment(event['@timestamp']).format('YYYY-MM-DD'),
              _type: TYPE_NAME,
              _id: event['@id'],
            },
          },
          event,
        ]),
      },
      function(err, resp) {
        if (err) {
          res
            .send(err)
            .type('application/json')
            .status(500);
        } else {
          res
            .status(200)
            .type('application/json')
            .send({});
        }
      }
    );
  }
});

app.listen(port, () => {
  console.log(`es-ingester listening on port ${port}!`);
});