const fs = require('fs');
const express = require('express');
const jwt = require('jsonwebtoken');
const bodyParser = require('body-parser');
const elasticsearch = require('elasticsearch');
const moment = require('moment');
const _ = require('lodash');

const DEBUG = !!process.env.DEBUG || false;
const LOG_LEVEL = process.env.LOG_LEVEL || 'debug';
const PORT = process.env.PORT || 9000;
const CONFIG_PATH = process.env.CONFIG_PATH || './config.json';
const CONFIG = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
const ES_HOST = process.env.ES_HOST || CONFIG.elasticsearch.host || 'localhost';
const ES_PORT = process.env.ES_PORT || CONFIG.elasticsearch.port || 9200;
const ES_PROTOCOL = process.env.ES_PROTOCOL || CONFIG.elasticsearch.protocol || 'http';
const ES_AUTH = process.env.ES_AUTH || CONFIG.elasticsearch.auth;

const INDEX_NAME = 'analytics';
const TYPE_NAME = 'analytics';
const TEMPLATE_PATH = './template.json';
const TEMPLATE = JSON.parse(fs.readFileSync(TEMPLATE_PATH, 'utf8'));

Array.prototype.flatMap = function(lambda) {
  return Array.prototype.concat.apply([], this.map(lambda));
};

const esHostConfig = {
  host: ES_HOST,
  auth: ES_AUTH,
  protocol: ES_PROTOCOL,
  port: ES_PORT,
};
console.log('Connecting to elastic', esHostConfig);

const client = new elasticsearch.Client({
  host: [esHostConfig],
  log: LOG_LEVEL,
});

client.indices.putTemplate(
  {
    id: 'analytics-tpl',
    body: TEMPLATE,
  },
  (error, response) => {
    if (error) {
      console.log('Error creating template', error);
    } else {
      console.log('Template updated', response);
    }
  }
);

const app = express().use(bodyParser.json());

app.post('/api/v1/events', (req, res) => {
  const body = req.body;
  if (!body || !Array.isArray(body)) {
    return res.sendStatus(400);
  } else {
    client.bulk(
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

app.get('/api/v1/events', (req, res) => {
  const type = req.param('@type');
  const serviceId = req.param('@serviceId');

  const from = req.param('from');
  const to = req.param('to') || moment().valueOf();

  const pageSize = req.param('pageSize') || 50;
  const pageNum = req.param('pageNum') || 1;
  const pageFrom = (pageNum - 1) * pageSize;

  const rangeQuery = {
    lte: to,
  };
  if (from) {
    rangeQuery['gte'] = from;
  }

  const filters = [{ range: { '@timestamp': rangeQuery } }];
  if (serviceId) {
    filters.push({ term: { '@service.raw': serviceId } });
  }
  if (type) {
    filters.push({ term: { '@type': type } });
  }

  client.search(
    {
      index: INDEX_NAME + '-*',
      body: {
        size: pageSize,
        from: pageFrom,
        query: {
          bool: {
            filter: filters,
          },
        },
        sort: {
          '@timestamp': { order: 'desc' },
        },
      },
    },
    handleError(req, res)(response => {
      res
        .status(200)
        .type('application/json')
        .send({
          events: response.hits.hits.map(h => h._source),
        });
    })
  );
});

app.get('/api/v1/events/:type/_count', (req, res) => {
  const filters = prepareFilters(req);
  client.search(
    {
      index: INDEX_NAME + '-*',
      body: {
        size: 0,
        query: {
          bool: {
            must: filters,
          },
        },
      },
    },
    handleError(req, res)(response => {
      res
        .status(200)
        .type('application/json')
        .send({
          count: response.hits.total,
        });
    })
  );
});

app.get('/api/v1/events/:type/:field/_sum', (req, res) => {
  aggregation('sum', req, res);
});

app.get('/api/v1/events/:type/:field/_avg', (req, res) => {
  aggregation('avg', req, res);
});

app.get('/api/v1/events/:type/:field/_piechart', (req, res) => {
  const filters = prepareFilters(req);
  const size = req.param('size') || 20;
  const field = req.param('field');
  client.search(
    {
      index: INDEX_NAME + '-*',
      body: {
        size: 0,
        query: {
          bool: {
            must: filters,
          },
        },
        aggs: {
          codes: {
            terms: {
              field: field,
              order: {
                _term: 'asc',
              },
              size: size,
            },
          },
        },
      },
    },
    handleError(req, res)(response => {
      const pie = response.aggregations.codes.buckets.map(data => ({
        name: `${data.key}`,
        y: data.doc_count,
      }));
      res
        .status(200)
        .type('application/json')
        .send({
          series: [
            {
              name: 'Pie Chart',
              colorByPoint: true,
              data: pie,
            },
          ],
        });
    })
  );
});

app.get('/api/v1/events/:type/:field/_histogram/stats', (req, res) => {
  const filters = prepareFilters(req);
  const chart = req.param('chart') || 'areaspline';
  const interval = req.param('interval') || calcInterval(req);
  const size = req.param('size') || 20;
  const field = req.param('field');
  client.search(
    {
      index: INDEX_NAME + '-*',
      body: {
        size: 0,
        query: {
          bool: {
            must: filters,
          },
        },
        aggs: {
          meanOverTime: {
            date_histogram: {
              field: '@timestamp',
              interval: interval,
            },
            aggs: {
              stats: {
                extended_stats: {
                  field: field,
                },
              },
            },
          },
        },
      },
    },
    handleError(req, res)(response => {
      const bucket = response.aggregations.meanOverTime.buckets;
      res
        .status(200)
        .type('application/json')
        .send({
          chart: {
            type: chart,
          },
          series: [
            extractSerie(bucket, 'count', b => b.stats.count || 0.0),
            extractSerie(bucket, 'min', b => b.stats.min || 0.0),
            extractSerie(bucket, 'max', b => b.stats.max || 0.0),
            extractSerie(bucket, 'avg', b => b.stats.avg || 0.0),
            extractSerie(bucket, 'std deviation', b => b.stats.std_deviation || 0.0),
          ],
        });
    })
  );
});

app.get('/api/v1/events/:type/:field/_histogram/percentiles', (req, res) => {
  const filters = prepareFilters(req);
  const chart = req.param('chart') || 'areaspline';
  const interval = req.param('interval') || calcInterval(req);
  const size = req.param('size') || 20;
  const field = req.param('field');
  client.search(
    {
      index: INDEX_NAME + '-*',
      body: {
        size: 0,
        query: {
          bool: {
            must: filters,
          },
        },
        aggs: {
          meanOverTime: {
            date_histogram: {
              field: '@timestamp',
              interval: interval,
            },
            aggs: {
              stats: {
                percentiles: {
                  field: field,
                },
              },
            },
          },
        },
      },
    },
    handleError(req, res)(response => {
      const bucket = response.aggregations.meanOverTime.buckets;
      res
        .status(200)
        .type('application/json')
        .send({
          chart: {
            type: chart,
          },
          series: [
            extractSerie(bucket, '1.0', b => b.stats.values['1.0'] || 0.0),
            extractSerie(bucket, '5.0', b => b.stats.values['5.0'] || 0.0),
            extractSerie(bucket, '25.0', b => b.stats.values['25.0'] || 0.0),
            extractSerie(bucket, '50.0', b => b.stats.values['50.0'] || 0.0),
            extractSerie(bucket, '75.0', b => b.stats.values['75.0'] || 0.0),
            extractSerie(bucket, '95.0', b => b.stats.values['95.0'] || 0.0),
            extractSerie(bucket, '99.0', b => b.stats.values['99.0'] || 0.0),
          ],
        });
    })
  );
});

app.get('/api/v1/events/httpStatus/_histogram', (req, res) => {
  const filters = prepareFilters(req);
  client.search(
    {
      index: INDEX_NAME + '-*',
      body: {
        size: 0,
        query: {
          bool: {
            must: filters,
          },
        },
        aggs: {
          codes: {
            aggs: {
              codesOverTime: {
                date_histogram: {
                  interval: 'hour',
                  field: '@timestamp',
                },
              },
            },
            range: {
              ranges: [
                {
                  from: 100,
                  to: 199,
                  key: '1**',
                },
                {
                  from: 200,
                  to: 299,
                  key: '2**',
                },
                {
                  from: 300,
                  to: 399,
                  key: '3**',
                },
                {
                  from: 400,
                  to: 499,
                  key: '4**',
                },
                {
                  from: 500,
                  to: 599,
                  key: '5**',
                },
              ],
              field: 'status',
              keyed: true,
            },
          },
        },
      },
    },
    handleError(req, res)(response => {
      let buckets;
      if (
        response.aggregations &&
        response.aggregations.codes &&
        response.aggregations.codes.buckets &&
        response.aggregations.codes.buckets.length > 0
      ) {
        buckets = response.aggregations.codes.buckets;
      } else {
        buckets = [];
      }

      const series = Object.keys(buckets)
        .filter(
          code =>
            buckets[code].codesOverTime &&
            buckets[code].codesOverTime.buckets &&
            buckets[code].codesOverTime.buckets.length > 0
        )
        .map(code => {
          return {
            name: code,
            count: buckets[code].doc_count,
            data: buckets[code].codesOverTime.buckets.map(o => [o.key, o.doc_count]),
          };
        });
      res
        .status(200)
        .type('application/json')
        .send({
          chart: { type: 'areaspline' },
          series: series,
        });
    })
  );
});

function aggregation(operation, req, res) {
  const field = req.param('field');
  const filters = prepareFilters(req);
  client.search(
    {
      index: INDEX_NAME + '-*',
      body: {
        size: 0,
        query: {
          bool: {
            must: filters,
          },
        },
        aggs: {
          [operation]: {
            [operation]: {
              field: field,
            },
          },
        },
      },
    },
    handleError(req, res)(response => {
      res
        .status(200)
        .type('application/json')
        .send({
          [field]: response.aggregations[operation].value,
        });
    })
  );
}

const handleError = (req, res) => func => (err, response) => {
  if (err) {
    res
      .status(500)
      .type('application/json')
      .send({
        error: 'Error',
      });
  } else {
    func(response);
  }
};

function calcInterval(req) {
  const from = req.param('from');
  const to = req.param('to');
  const fromMoment = from ? moment(from) : moment();
  const toMoment = to ? moment(to) : moment();

  const duration = moment.duration(toMoment.diff(fromMoment));
  const days = duration.asDays();
  const halfDays = days * 2;
  const month = duration.asMonths();
  const years = duration.asYears();

  if (!from) {
    return 'month';
  }

  if (years > 0) {
    return 'month';
  } else if (month > 2) {
    return 'week';
  } else if (month > 2) {
    return 'week';
  } else if (month > 0) {
    return 'day';
  } else if (halfDays > 0 && days <= 31) {
    return 'hour';
  } else {
    return 'minute';
  }
}

function extractSerie(bucket, name, extract, extra = {}) {
  const histogram = bucket.map(b => [b.key, extract(b)]);
  return {
    name: name,
    data: histogram,
    ...extra,
  };
}

function prepareFilters(req) {
  const from = req.param('from');
  const to = req.param('to');
  const toMoment = to ? moment(to) : moment();
  const services = req.param('services');

  const range = {
    format: 'date_optional_time',
    lte: toMoment.toISOString(),
  };
  if (from) {
    range['gte'] = moment(from).toISOString();
  }
  const filters = [
    {
      range: {
        '@timestamp': range,
      },
    },
    {
      terms: {
        '@type': ['GatewayEvent'],
      },
    },
  ];
  if (services) {
    filters.push({
      bool: {
        minimum_should_match: 1,
        should: [
          {
            bool: {
              must_not: {
                exists: {
                  field: '@product',
                },
              },
            },
          },
          {
            terms: {
              '@product': services.split(','),
            },
          },
        ],
      },
    });
  }
  return filters;
}
app.listen(PORT, () => {
  console.log('\n# Welcome to the Otoroshi Elasticsearch daemon');
  console.log(`# The daemon status is available at http://127.0.0.1:${PORT}\n`);
});
