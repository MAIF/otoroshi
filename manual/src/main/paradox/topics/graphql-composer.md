# GraphQL Composer Plugin

<div style="display: flex; align-items: center; gap: .5rem; margin-bottom: 1rem">
<span style="font-weight: bold">Route plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.GraphQLBackend">GraphQL Composer</a>
</div>

@@include[experimental.md](../includes/experimental.md) { .experimental-feature }

> GraphQL is a query language for APIs and a runtime for fulfilling those queries with your existing data. GraphQL provides a complete and understandable description of the data in your API, gives clients the power to ask for exactly what they need and nothing more, makes it easier to evolve APIs over time, and enables powerful developer tools.
[Official GraphQL website](https://graphql.org/)

APIs RESTful and GraphQL development has become one of the most popular activities for companies as well as users in recent times. In fast scaling companies, the multiplication of clients can cause the number of API needs to grow at scale.

Otoroshi comes with a solution to create and meet your customers' needs without constantly creating and recreating APIs: the `GraphQL composer plugin`. The GraphQL Composer is an useful plugin to build an GraphQL API from multiples differents sources. These sources can be REST apis, GraphQL api, raw JSON or WASM binary or anything that supports the HTTP protocol. 

@@@ div { .centered-img }
<img src="../imgs/graphql-composer.png" width="620px" />
@@@


## Tutorial

Let's set up the plugin to align with the specified models:

A user model with attributes for name and password.
A country model with attributes for name and its associated users.

The plugin provides custom directives to compose your API. A `directive` decorates part of a GraphQL schema or operation with additional configuration. Directives are preceded by the **@** character, like so:

* @ref:[rest](#directives) : to call a http rest service with dynamic path params
* @ref:[permission](#directives) : to restrict the access to the sensitive field
* @ref:[graphql](#directives) : to call a graphQL service by passing a url and the associated query

The GraphQL schema of our tutorial should look like this

```graphql
type Country {
  name: String
  users: [User] @rest(url: "http://localhost:5000/countries/${item.name}/users")
}

type User {
  name: String
  password: String @password(value: "ADMIN")
}

type Query {
  users: [User] @rest(url: "http://localhost:5000/users", paginate: true)
  user(id: String): User @rest(url: "http://localhost:5000/users/${params.id}")
  countries: [Country] @graphql(url: "https://countries.trevorblades.com", query: "{ countries { name }}", paginate: true)
}
```

Now that we've covered the fundamentals of GraphQL Composer, let's proceed with setting it up in our project :

* set up a route containing the GraphQL Composer plugin
* configure the plugin with the appropriate schema
* conduct a test to ensure everything is functioning correctly

@@@ div { .centered-img }
<img src="../imgs/countries-api.png" width="620px" />
@@@

### Setup your environment

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Create our countries API

Let's create the `route` with few informations :

* name: `My countries API`
* frontend: `countries-api.oto.tools`
* plugins: `GraphQL composer` plugin

Let's make a request call through the Otoroshi Admin API (with the default apikey), like the example below
```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/routes' \
  -d '{
  "id": "countries-api",
  "name": "My countries API",
  "frontend": {
    "domains": ["countries.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "request.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ],
    "load_balancing": {
      "type": "RoundRobin"
    }
  },
  "plugins": [
    {
      "plugin": "cp:otoroshi.next.plugins.GraphQLBackend"
    }
  ]
}' \
  -H "Content-type: application/json" \
  -u admin-api-apikey-id:admin-api-apikey-secret
```

### Configure our countries API 

Let's continue our API by updating the configuration of the GraphQL plugin with the initial schema.

```sh
curl -X PUT 'http://otoroshi-api.oto.tools:8080/api/routes/countries-api' \
  -d '{
    "id": "countries-api",
    "name": "My countries API",
    "frontend": {
        "domains": [
            "countries.oto.tools"
        ]
    },
    "backend": {
        "targets": [
            {
                "hostname": "request.otoroshi.io",
                "port": 443,
                "tls": true
            }
        ],
        "load_balancing": {
            "type": "RoundRobin"
        }
    },
    "plugins": [
        {
            "enabled": true,
            "plugin": "cp:otoroshi.next.plugins.GraphQLBackend",
            "config": {
                "schema": "type Country {\n  name: String\n  users: [User] @rest(url: \"http://localhost:8181/countries/${item.name}/users\", headers: \"{}\")\n}\n\ntype Query {\n  users: [User] @rest(url: \"http://localhost:8181/users\", paginate: true, headers: \"{}\")\n  user(id: String): User @rest(url: \"http://localhost:8181/users/${params.id}\")\n  countries: [Country] @graphql(url: \"https://countries.trevorblades.com\", query: \"{ countries { name }}\", paginate: true)\ntype User {\n  name: String\n  password: String }\n"
            }
        }
    ]
}' \
  -H "Content-type: application/json" \
  -u admin-api-apikey-id:admin-api-apikey-secret
```

Our created route expects an API, exposed on the localhost:8181, designed to retrieve users based on their countries. 

Let's develop this API using NodeJS. The API uses express as http server.

```js
const express = require('express')

const app = express()

const users = [
  {
    name: 'Joe',
    password: 'password'
  },
  {
    name: 'John',
    password: 'password2'
  }
]

const countries = [
  {
    name: 'Andorra',
    users: [users[0]]
  },
  {
    name: 'United Arab Emirates',
    users: [users[1]]
  }
]

app.get('/users', (_, res) => {
  return res.json(users)
})

app.get(`/users/:name`, (req, res) => {
  res.json(users.find(u => u.name === req.params.name))
})

app.get('/countries/:id/users', (req, res) => {
  const country = countries.find(c => c.name === req.params.id)

  if (country) 
    return res.json(country.users)
  else 
    return res.json([])
})

app.listen(8181, () => {
  console.log(`Listening on 8181`)
});

```

Let's initiate our first request to the countries API.

```sh
curl 'countries.oto.tools:9999/' \
--header 'Content-Type: application/json' \
--data-binary @- << EOF
{
    "query": "{\n    countries {\n        name\n        users {\n            name\n   }\n    }\n}"
}
EOF
```

You should see the following content in your terminal.

```json
{
  "data": { 
    "countries": [
      { 
        "name":"Andorra",
        "users": [
          { "name":"Joe" }
        ]
      }
    ]
  }
}
```

The call graph should looks like


1. Initiate a request to https://countries.trevorblades.com
2. For each country retrieved:
   - extract the `name` field
   - sends a request to http://localhost:8181/countries/${country}/users to retrieve the list of users associated with this country.

You may have noticed that we appended an argument called `pagniate` to the end of the graphql directive. This argument enables pagination for the client, allowing it to accept parameters such as limit and offset. The plugin utilizes these parameters to filter and streamline the content.

Let's initiate a new call that doesn't specify any country.

```sh
curl 'countries.oto.tools:9999/' \
--header 'Content-Type: application/json' \
--data-binary @- << EOF
{
    "query": "{\n    countries(limit: 0) {\n        name\n        users {\n            name\n   }\n    }\n}"
}
EOF
```

You should see the following content in your terminal.

```json
{
  "data": { 
    "countries": []
  }
}
```

Let's move on to the next section to secure sensitive field of our API.

### Basics of permissions 

The permission directives have been established to safeguard the fields within the GraphQL schema. The validation process commences by generating a context for all incoming requests, derived from the list of paths specified in the permissions field of the plugin. These permissions paths can reference various components of the request data (such as URL, headers, etc.), user credentials (such as API key, etc.), and details regarding the matched route. Subsequently, the process verifies that the specified value or values are present within the context.

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">Permission</span>
<button class="simple-block-button">Close</button>
</div>

*Arguments : value and unauthorized_value*

The permission directive is designed to to secure a field on **one** value. The directive checks that a specific value is present in the `context`.

Two arguments are available : `value` which is mandatory and specifies the expected value. Optionally, `unauthorized_value`, which can be utilized to indicate the rejection message in the outgoing response.

**Example**
```js
type User {
    id: String @permission(
        value: "FOO", 
        unauthorized_value: "You're not authorized to get this field")
}
```
@@@

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">All permissions</span>
<button class="simple-block-button">Close</button>
</div>

*Arguments : values and unauthorized_value*

This directive is presumably the same as the previous one except that it takes a list of values.

**Example**
```js
type User {
    id: String @allpermissions(
        values: ["FOO", "BAR"], 
        unauthorized_value: "FOO and BAR could not be found")
}
```
@@@

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">One permissions of</span>
<button class="simple-block-button">Close</button>
</div>
*Arguments : values and unauthorized_value*

This directive takes a list of values and validate that one of them is in the context.

**Example**
```js
type User {
    id: String @onePermissionsOf(
        values: ["FOO", "BAR"], 
        unauthorized_value: "FOO or BAR could not be found")
}
```
@@@

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">Authorize</span>
<button class="simple-block-button">Close</button>
</div>

*Arguments : path, value and unauthorized_value*

The authorize directive has one more required argument, named `path`, which indicates the path to value, in the context. Unlike the last three directives, the authorize directive doesn't search in the entire context but at the specified path.

**Example**
```js
type User {
    id: String @authorize(
        path: "$.raw_request.headers.foo", 
        value: "BAR", 
        unauthorized_value: "Bar could not be found in the foo header")
}
```
@@@

Let's restrict the password field to the users that comes with a `role` header of the value `ADMIN`.

1. Patch the configuration of the API by adding the permissions in the configuration of the plugin.
```json
...
 "permissions": ["$.raw_request.headers.role"]
...
```

1. Add an directive on the password field in the schema
```graphql
type User {
  name: String
  password: String @permission(value: "ADMIN")
}
```

Let's make a call with the role header

```sh
curl 'countries.oto.tools:9999/' \
--header 'Content-Type: application/json' \
--header 'role: ADMIN'
--data-binary @- << EOF
{
    "query": "{\n    countries(limit: 0) {\n name\n  users {\n name\n password\n   }\n    }\n}"
}
EOF
```

Now try to change the value of the role header

```sh
curl 'countries.oto.tools:9999/' \
--header 'Content-Type: application/json' \
--header 'role: USER'
--data-binary @- << EOF
{
    "query": "{\n    countries(limit: 0) {\n name\n  users {\n name\n password\n   }\n    }\n}"
}
EOF
```

The error message should look like 

```json
{
  "errors": [
    {
      "message": "You're not authorized",
      "path": [
        "countries",
        0,
        "users",
        0,
        "password"
      ],
      ...
    }
  ]
}
```


# Glossary

## Directives

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">Rest</span>
<button class="simple-block-button">Close</button>
</div>

*Arguments : url, method, headers, timeout, data, response_path, response_filter, limit, offset, paginate*

The rest directive is used to expose servers that communicate using the http protocol. The only required argument is the `url`.

**Example**
```js
type Query {
    users(limit: Int, offset: Int): [User] @rest(url: "http://foo.oto.tools/users", method: "GET")
}
```

It can be placed on the field of a query and type. To custom your url queries, you can use the path parameter and another field with respectively, `params` and `item` variables.

**Example**
```js
type Country {
  name: String
  phone: String
  users: [User] @rest(url: "http://foo.oto.tools/users/${item.name}")
}

type Query {
  user(id: String): User @rest(url: "http://foo.oto.tools/users/${params.id}")
}
```
@@@

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">GraphQL</span>
<button class="simple-block-button">Close</button>
</div>

*Arguments : url, method, headers, timeout, query, data, response_path, response_filter, limit, offset, paginate*

The rest directive is used to call an other graphql server.

The required argument are the `url` and the `query`.

**Example**
```js
type Query {
    countries: [Country] @graphql(url: "https://countries.trevorblades.com/", query: "{ countries { name phone }}")
}

type Country {
    name: String
    phone: String
}
```
@@@

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">Soap</span>
<button class="simple-block-button">Close</button>
</div>
*Arguments: all following arguments*

The soap directive is used to call a soap service. 

```js
type Query {
    randomNumber: String @soap(
        jq_response_filter: ".[\"soap:Envelope\"] | .[\"soap:Body\"] | .[\"m:NumberToWordsResponse\"] | .[\"m:NumberToWordsResult\"]", 
        url: "https://www.dataaccess.com/webservicesserver/numberconversion.wso", 
        envelope: "<?xml version=\"1.0\" encoding=\"utf-8\"?> \n  <soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">   \n  <soap:Body>     \n    <NumberToWords xmlns=\"http://www.dataaccess.com/webservicesserver/\">       \n      <ubiNum>12</ubiNum>     \n    </NumberToWords>   \n  </soap:Body> \n</soap:Envelope>")
}
```


##### Specific arguments

| Argument                    | Type      | Optional | Default value |
| --------------------------- | --------- | -------- | ------------- |
| envelope                    | *STRING*  | Required |               |
| url                         | *STRING*  | x        |               |
| action                      | *STRING*  | x        |               |
| preserve_query              | *BOOLEAN* | Required | true          |
| charset                     | *STRING*  | x        |               |
| convert_request_body_to_xml | *BOOLEAN* | Required | true          |
| jq_request_filter           | *STRING*  | x        |               |
| jq_response_filter          | *STRING*  | x        |               |

@@@

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">JSON</span>
<button class="simple-block-button">Close</button>
</div>
*Arguments: path, json, paginate*

The json directive can be used to expose static data or mocked data. The first usage is to defined a raw stringify JSON in the `data` argument. The second usage is to set data in the predefined field of the GraphQL plugin composer and to specify a path in the `path` argument.

**Example**
```js
type Query {
    users_from_raw_data: [User] @json(data: "[{\"firstname\":\"Foo\",\"name\":\"Bar\"}]")
    users_from_predefined_data: [User] @json(path: "users")
}
```
@@@

@@@div { .simple-block }

<div class="simple-block-toggle">
<span class="simple-block-title">Mock</span>
<button class="simple-block-button">Close</button>
</div>
*Arguments: url*

The mock directive is to used with the Mock Responses Plugin, also named `Charlatan`. This directive can be interesting to mock your schema and start to use your Otoroshi route before starting to develop the underlying service.

**Example**
```js
type Query {
    users: @mock(url: "/users")
}
```

This example supposes that the Mock Responses plugin is set on the route's feed, and that an endpoint `/users` is available.

@@@

### List of directive arguments

| Argument           | Type             | Optional                    | Default value |
| ------------------ | ---------------- | --------------------------- | ------------- |
| url                | *STRING*         |                             |               |
| method             | *STRING*         | x                           | GET           |
| headers            | *STRING*         | x                           |               |
| timeout            | *INT*            | x                           | 5000          |
| data               | *STRING*         | x                           |               |
| path               | *STRING*         | x (only for json directive) |               |
| query              | *STRING*         | x                           |               |
| response_path      | *STRING*         | x                           |               |
| response_filter    | *STRING*         | x                           |               |
| limit              | *INT*            | x                           |               |
| offset             | *INT*            | x                           |               |
| value              | *STRING*         |                             |               |
| values             | LIST of *STRING* |                             |
| path               | *STRING*         |                             |               |
| paginate           | *BOOLEAN*        | x                           |               |
| unauthorized_value | *STRING*         | x (only for permissions directive) |               |
