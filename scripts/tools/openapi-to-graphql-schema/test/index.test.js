const OTOROSHI = {
  api: 'http://otoroshi-api.oto.tools:9999',
  credentials: 'YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA==',
}
const SKIP_CREATION_ROUTE = process.env.SKIP_CREATION_ROUTE || true;
const DOMAIN_ROUTE = process.env.DOMAIN_ROUTE || 'graphql-admin-api-next-gen.oto.tools'

const fs = require('fs');
const fetch = require('cross-fetch');

const schema = fs
  .readFileSync('../dist/admin-api-graphql.graphql', { encoding: 'utf8', flag: 'r' })

const rawGraphqlQueries = fs
  .readFileSync('../dist/queries.graphql', { encoding: 'utf8', flag: 'r' })
  .trim()
  .split("\n")
  .reduce((acc, query) => {
    if (query.startsWith('query ')) {
      if (acc.inQuery)
        return {
          inQuery: true,
          queries: [...acc.queries, acc.current.join("\n")],
          current: [query]
        }
      else
        return {
          ...acc,
          inQuery: true,
          current: [...acc.current, query]
        }
    }
    else
      return {
        ...acc,
        inQuery: true,
        current: [...acc.current, query]
      }

  }, {
    inQuery: false,
    current: [],
    queries: []
  })

const graphqlQueries = [
  ...rawGraphqlQueries.queries,
  rawGraphqlQueries.current.join("\n")
].reduce((acc, r) => ({
  ...acc,
  [r.split('\n')[0].replace('query ', '').match(/^([\w\-]+)/g)[0]]: r
}), {})

const queries = schema
  .split("\n")
  .reduce((acc, c) => {
    if (acc.reading) {
      if (c.startsWith("}"))
        return {
          ...acc,
          reading: false
        }
      return {
        ...acc,
        queries: [...acc.queries, c]
      }
    } else if (c.includes("type Query")) {
      return {
        ...acc,
        reading: true
      }
    }

    return acc
  }, {
    reading: false,
    queries: []
  })
  .queries
  .map(query => {
    const parts = query.replace(')', '')
      .split("(")
      .map(q => q.trim())

    const options = parts
      .find(p => p.startsWith('url:'))
      .split(",")
      .map(opt => opt.split(": ")
        .map(r => r.trim())
        .map(r => r.replace(/"/g, "")))

    return {
      name: parts[0].split(":")[0],
      url: options.find(f => f[0] === "url")[1],
      method: options.find(f => f[0] === "method")[1],
      headers: options.find(f => f[0] === "headers")[1]
    }
  })

const route = require('./route-template/admin-api-route.json');
const _ = require('lodash');

beforeAll(async () => {
  const res = await (SKIP_CREATION_ROUTE ? Promise.resolve({ status: 200 }) : fetch(`${OTOROSHI.api}/api/experimental/routes`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      Authorization: `Basic ${OTOROSHI.credentials}`
    },
    body: JSON.stringify({
      ...route,
      frontend: {
        ...route.frontend,
        domains: [DOMAIN_ROUTE]
      },
      plugins: [
        {
          ...route.plugins[0],
          config: {
            ...route.plugins[0].config,
            schema
          }
        }
      ]
    })
  }))

  if (res.status > 299) {
    const message = await res.json()
    console.log(`${res.status}: ${JSON.stringify(message, null, 4)}`)
  }
});

const compare = function (a, b) {
  let result = {
    different: [],
    missing_from_first: [],
    missing_from_second: []
  };

  _.reduce(a, function (result, value, key) {
    if (b?.hasOwnProperty(key)) {
      if (_.isEqual(value, b[key])) {
        return result;
      } else {
        if (typeof (a[key]) != typeof ({}) || typeof (b[key]) != typeof ({})) {
          //dead end.
          result.different.push(key);
          return result;
        } else {
          let deeper = compare(a[key], b[key]);
          result.different = result.different.concat(_.map(deeper.different, (sub_path) => {
            return key + "." + sub_path;
          }));

          result.missing_from_second = result.missing_from_second.concat(_.map(deeper.missing_from_second, (sub_path) => {
            return key + "." + sub_path;
          }));

          result.missing_from_first = result.missing_from_first.concat(_.map(deeper.missing_from_first, (sub_path) => {
            return key + "." + sub_path;
          }));
          return result;
        }
      }
    } else {
      result.missing_from_second.push(key);
      return result;
    }
  }, result);

  _.reduce(b, function (result, value, key) {
    if (a?.hasOwnProperty(key)) {
      return result;
    } else {
      result.missing_from_first.push(key);
      return result;
    }
  }, result);

  return result;
}

const removeUncomparableFields = obj => {
  if (!obj)
    return {};

  ['id', 'password'].forEach(field => {
    delete obj[field]
  });
  return obj
}

const testGraphQLAndOtoResponse = (name, graphql, oto) => {
  let res = compare(removeUncomparableFields(graphql), removeUncomparableFields(oto))

  // removes clientId, clientSecret, cauz each call generated new ones
  if (name === "newApikey") {
    res = {
      ...res,
      different: res.different.filter(p => !["clientId", "clientSecret"].includes(p))
    }
  }

  if (res.different.length > 0) {
    console.log(`${name}: ${JSON.stringify(res, null, 2)}`)
    console.log(graphql)
    console.log(oto)

    // /api/admin-sessions
    // adminSessions query n'indique pas de type array dans l'openapi et le schema
  }
  expect(res.different.length === 0).toBe(true)
}

describe('Queries', () => {
  queries.map(({ name, url, method, headers }) => {
    test(name, async () => {
      const otoroshiResponse = await fetch(`${OTOROSHI.api}${new URL(url).pathname}`, {
        headers: {
          ...headers,
          Authorization: `Basic ${OTOROSHI.credentials}`
        },
        method
      })
        .then(r => r.json())

      const graphqlOtoroshiResponse = await fetch(`http://${DOMAIN_ROUTE}:9999`, {
        headers: {
          ...headers,
          // Authorization: `Basic ${OTOROSHI.credentials}`
        },
        body: JSON.stringify({
          query: graphqlQueries[name],
          letiables: {}
        }),
        method: 'POST'
      })
        .then(r => r.json())
        .then(r => {
          if (r.data)
            return r.data[name]
          else
            return r.error
        })

      if (Array.isArray(graphqlOtoroshiResponse))
        graphqlOtoroshiResponse.forEach(r => testGraphQLAndOtoResponse(name, r, otoroshiResponse))
      else if (Array.isArray(otoroshiResponse))
        otoroshiResponse.forEach(r => testGraphQLAndOtoResponse(name, graphqlOtoroshiResponse, r))
      else {
        testGraphQLAndOtoResponse(name, graphqlOtoroshiResponse, otoroshiResponse)
      }
    });
  })
})
