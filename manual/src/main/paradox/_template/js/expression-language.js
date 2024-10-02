document.addEventListener("DOMContentLoaded", function () {
  if (document.getElementById("expression-language")) {
    const expressions = [
      {
        name: "Date information",
        editor: [
          {
            expression: "${date}",
            description:
              "the date corresponding to the moment where the request passed. The field is in Date Time format, with default format *yyy-mm-ddTHH:MM:SS.sss*",
          },
          {
            expression: "${date.format('<format>')}",
            description:
              "same field that the previous but with a chosen format.",
          },
        ],
        values: [
          {
            expression: "${date}",
            description: "2021-11-25T10:53:25.366+01:00",
          },
          {
            expression: "${date.format('yyy-MM-dd')}",
            description: "2021-11-25",
          },
        ],
      },
      {
        name: "Service information",
        editor: [
          {
            expression: "${service.domain}",
            description: "matched service domain by the query",
          },
          {
            expression: "${service.subdomain}",
            description: "matched service subdomain by the query",
          },
          {
            expression: "${service.tld}",
            description: "matched top level domain by the query",
          },
          {
            expression: "${service.env}",
            description: "Otoroshi environment of the matched service",
          },
          { expression: "${service.id}", description: "matched service id" },
          {
            expression: "${service.name}",
            description: "matched service name",
          },
          {
            expression: "${service.groups[<field>:<default value>]}",
            description:
              "get nth group of the service or returned the default value",
          },
          {
            expression: "${service.groups[<field>]}",
            description:
              "get nth group of the service or returned `no-group-<field>` as value",
          },
          {
            expression: "${service.metadata.<field>:<default value>}",
            description:
              "get the metadata of the service or returned the default value",
          },
          {
            expression: "${service.metadata.<field>}",
            description:
              "get the metadata of the service or returned `no-meta-<field>` as value",
          },
        ],
        values: [
          {
            expression: "${service.domain}",
            description: "myservice.oto.tools",
          },
          { expression: "${service.subdomain}", description: "myservice" },
          { expression: "${service.tld}", description: "oto.tools" },
          { expression: "${service.env}", description: "prod" },
          { expression: "${service.id}", description: "GnzxRRWi..." },
          { expression: "${service.name}", description: "service" },
          {
            expression: "${service.groups[0:'unkown group']}",
            description: "unknown-group",
          },
          { expression: "${service.groups[0]}", description: "default-group" },
          {
            expression: "${service.metadata.test:'default-value'}",
            description: "default-value",
          },
          { expression: "${service.metadata.foo}", description: "bar" },
        ],
      },
      {
        name: "Request information",
        editor: [
          {
            expression: "${req.fullUrl}",
            description:
              "the complete URL with protocol, host and relative URI",
          },
          { expression: "${req.path}", description: "the path of the request" },
          { expression: "${req.uri}", description: "the relative URI" },
          { expression: "${req.host}", description: "the host of the request" },
          {
            expression: "${req.domain}",
            description: "the domain of the request",
          },
          {
            expression: "${req.method}",
            description: "the method of the request",
          },
          {
            expression: "${req.protocol}",
            description: "the protocol of the request",
          },
          {
            expression: "${req.headers.<header>:'<default value>'}",
            description: "get specific header of the request",
          },
          {
            expression: "${req.headers.<header>}",
            description:
              "get specific header of the request or get `no-header-<header>` as value",
          },
          {
            expression: "${req.query.<query param>:'<default value>'}",
            description: "get specific query param of the request",
          },
          {
            expression: "${req.query.<query param>}",
            description:
              "get specific query param of the request or get `no-path-param-<path param>` as value",
          },
          {
            expression: "${req.pathparams.<path param>:'<default value>'}",
            description: "get specific path param of the request",
          },
          {
            expression: "${req.pathparams.<path param>}",
            description:
              "get specific path param of the request or get `no-path-param-<path param>` as value",
          },
        ],
        values: [
          {
            expression: "${req.fullUrl}",
            description: "http://api.oto.tools:8080/api/?foo=bar",
          },
          { expression: "${req.path}", description: "/api/" },
          { expression: "${req.uri}", description: "/api/?foo=bar" },
          { expression: "${req.host}", description: "api.oto.tools:8080" },
          { expression: "${req.domain}", description: "api.oto.tools" },
          { expression: "${req.method}", description: "GET" },
          { expression: "${req.protocol}", description: "http" },
          {
            expression: "${req.headers.foob:default value>",
            description: "default value",
          },
          { expression: "${req.headers.foo}", description: "bar" },
          {
            expression: "${req.query.foob:default value}",
            description: "default value",
          },
          { expression: "${req.query.foo}", description: "bar" },
          {
            expression: "${req.pathparams.foob:'default value'}",
            description: "default value",
          },
          { expression: "${req.pathparams.foo}", description: "bar" },
        ],
      },
      {
        name: "Apikey information",
        editor: [
          {
            expression: "${apikey.name}",
            description: "if apikey is present, the client name of the apikey",
          },
          {
            expression: "${apikey.id}",
            description:
              "if apikey is present in the request, the client id of the apikey",
          },
          {
            expression: "${apikey.metadata.<field>:'<default value>'}",
            description:
              "if apikey is present, got the expected metadata, else got the default value",
          },
          {
            expression: "${apikey.metadata.<field>}",
            description:
              "if apikey is present, got the expected metadata, else got the default value `no-meta-<field>`",
          },
          {
            expression: "${apikey.tags[<field>:'<default value>']}",
            description:
              "if apikey is present, got the nth tags or the default value",
          },
          {
            expression: "${apikey.tags[<field>]}",
            description:
              "if apikey is present, got the nth tags or `no-tag-<field>` as value",
          },
        ],
        values: [
          {
            expression: "${apikey.name}",
            description: "Otoroshi Backoffice ApiKey",
          },
          { expression: "${apikey.id}", description: "admin-api-apikey-id" },
          {
            expression: "${apikey.metadata.myfield:'default value'}",
            description: "default value",
          },
          { expression: "${apikey.metadata.foo}", description: "bar" },
          {
            expression: "${apikey.tags['0':'no-found-tag']}",
            description: "no-found-tag",
          },
          { expression: "${apikey.tags['0']}", description: "one-tag" },
        ],
      },
      {
        name: "Token information",
        context: "Only on jwt verifier fields",
        editor: [
          {
            expression: "${token.<field>.replace('<a>','<b>')}",
            description:
              "get token field and replace a value by b or get `no-token-<field>` as value",
          },
          {
            expression: "${token.<field>.replaceAll('<a>','<b>')}",
            description:
              "get token field and replace **all** a value by b or get `no-token-<field>` as value",
          },
          {
            expression: "${token.<field>|token.<field-2>:<default value>}",
            description:
              "get claim of the token, ot the second claim of the token or a default value if not present",
          },
          {
            expression: "${token.<field>|token.<field-2>}",
            description:
              "get claim of the token or `no-token-$field-$field2` if not present",
          },
          {
            expression: "${token.<field>:<default value>}",
            description:
              "get claim of the token or a default value if not present",
          },
          {
            expression: "${token.<field>}",
            description: "get claim of the token",
          },
        ],
        values: [
          { expression: "${token.foo.replace('o','a')}", description: "fao" },
          {
            expression: "${token.foo.replaceAll('o','a')}",
            description: "faa",
          },
          {
            expression: "${token.foob|token.foob2:'not-found'}",
            description: "not-found",
          },
          { expression: "${token.foob|token.foo}", description: "foo" },
          {
            expression: "${token.foob:'not-found-foob'}",
            description: "not-found-foob",
          },
          { expression: "${token.foo}", description: "bar" },
        ],
      },
      {
        name: "System Environment information",
        editor: [
          {
            expression: "${env.<field>:<default value>}",
            description:
              "get system environment variable or a default value if not present",
          },
          {
            expression: "${env.<field>}",
            description:
              "get system environment variable or `no-env-var-<field>` if not present",
          },
        ],
        values: [
          {
            expression: "${env.java_h:'not-found-java_h'}",
            description: "not-found-java_h",
          },
          { expression: "${env.PATH}", description: "/usr/local/bin:" },
        ],
      },
      {
        name: "Environment information",
        editor: [
          {
            expression: "${config.<field>:<default value>}",
            description:
              "get environment variable or a default value if not present",
          },
          {
            expression: "${config.<field>}",
            description:
              "get environment variable or `no-config-<field>` if not present",
          },
        ],
        values: [
          {
            expression: "${config.http.ports:'not-found'}",
            description: "not-found",
          },
          { expression: "${config.http.port}", description: "8080" },
        ],
      },
      {
        name: "Context information",
        editor: [
          {
            expression: "${ctx.<field>.replace('<a>','<b>')}",
            description:
              "get field and replace a value  by b in the string, or set `no-ctx-<field>` as value",
          },
          {
            expression: "${ctx.<field>.replaceAll('<a>','<b>')}",
            description:
              "get field and replace all a value by b in the string, or set `no-ctx-<field>` as value",
          },
          {
            expression: "${ctx.<field>|ctx.<field-2>:<default value>}",
            description:
              "get field or if empty the second field, or the set the default value",
          },
          {
            expression: "${ctx.<field>|ctx.<field-2>}",
            description:
              "get field or if empty the second field, or the set `no-ctx-<field>-<field2>` as value",
          },
          {
            expression: "${ctx.<field>:<default value>}",
            description: "get field or the set the default value",
          },
          {
            expression: "${ctx.<field>}",
            description: "get field or the set `no-ctx-<field>` as value",
          },
          {
            expression: "${ctx.useragent.<field>}",
            description:
              "get user agent field or set `no-ctx-<field>` as value",
          },
          {
            expression: "${ctx.geolocation.<field>}",
            description:
              "get geolocation field or set `no-ctx-<field>` as value",
          },
        ],
        values: [
          { expression: "${ctx.foo.replace('o','a')}", description: "fao" },
          { expression: "${ctx.foo.replaceAll('o','a')}", description: "faa" },
          {
            expression: "${ctx.foob|ctx.foot:'not-found'}",
            description: "not-found",
          },
          { expression: "${ctx.foob|ctx.foo}", description: "bar" },
          { expression: "${ctx.foob:'other'}", description: "other" },
          { expression: "${ctx.foo}", description: "bar" },
          { expression: "${ctx.useragent.foo}", description: "no-ctx-foo" },
          { expression: "${ctx.geolocation.foo}", description: "no-ctx-foo" },
        ],
      },
      {
        name: "User information",
        context: "If call to a private app",
        editor: [
          { expression: "${user.name}", description: "get user name" },
          { expression: "${user.email}", description: "get user email" },
          {
            expression: "${user.metadata.<field>:<default value>}",
            description: "get metadata of user or get the default value",
          },
          {
            expression: "${user.metadata.<field>}",
            description: "get metadata of user or `no-meta-<field>` as value",
          },
          {
            expression: "${user.profile.<field>:<default value>}",
            description:
              "get field of profile of user or get the default value",
          },
          {
            expression: "${user.profile.<field>}",
            description:
              " get field of profile of user or `no-profile-<field>` as value",
          },
        ],
        values: [
          { expression: "${user.name}", description: "Otoroshi Admin" },
          { expression: "${user.email}", description: "admin@otoroshi.io" },
          {
            expression: "${user.metadata.username:'not-found'}",
            description: "not-found",
          },
          {
            expression: "${user.metadata.username}",
            description: "no-meta-username",
          },
          {
            expression: "${user.profile.username:'not-found'}",
            description: "not-found",
          },
          { expression: "${user.profile.name}", description: "Otoroshi Admin" },
        ],
      },
    ];

    const container = document.createElement("div");

    function createBloc(item, i) {
      const container = document.createElement("div");

      const h2 = document.createElement("h2");
      h2.textContent = item.name;
      container.appendChild(h2);

      if (item.context) {
        const span = document.createElement("span");
        span.textContent = item.context;
        span.style.fontStyle = "italic";
        container.appendChild(span);
      }

      const editor = document.createElement("div");
      editor.classList.add("editor");

      const editorContent = document.createElement("div");

      item.editor.forEach(({ expression, description }) => {
        const p = document.createElement("p");

        const code = document.createElement("code");
        code.textContent = expression;

        const span = document.createElement("span");
        span.textContent = description;

        p.appendChild(code);
        p.appendChild(span);

        editorContent.appendChild(p);
      });

      const valuesContent = document.createElement("div");

      valuesContent.appendChild(Example(item.name));

      item.values.forEach(({ expression, description }) => {
        const p = document.createElement("p");

        const code = document.createElement("code");
        code.textContent = expression;

        const span = document.createElement("span");
        span.classList.add("editor-value");
        span.textContent = description;

        p.appendChild(code);
        p.appendChild(span);

        valuesContent.appendChild(p);
      });

      editor.appendChild(editorContent);
      editor.appendChild(valuesContent);

      container.appendChild(editor);
      return container;
    }

    function Example(name) {
      const div = document.createElement("div");
      div.classList.add("editor-title");

      const span = document.createElement("span");
      span.textContent = name;

      div.appendChild(span);
      return div;
    }

    expressions.forEach((item) => {
      container.appendChild(createBloc(item));
    });

    document.getElementById("expressions").appendChild(container);

    container.style.background =
      "linear-gradient(to right, #eee 60%, #303740 40%)";
    container.style.padding = "12px";
  } else if (document.getElementById("data-exporters")) {
    function generateFilteringContent() {
      const filtering = [
        {
          expression: '{ "<key>": "<value>" }',
          description: "a field key with value",
          example: '{ "foo": "bar" }',
          exampleDescription: "Keep events with foo as key and bar as value",
        },
        {
          expression: '{ "<key>": { "$wildcard": "<value>*" } }',
          description: "a field starting with value",
          example: '{ "type": { "$wildcard": "Alert*" } }',
          exampleDescription: "Keep events with type field starting with Alert",
        },
        {
          expression: '{ "<key>": { "<key2>": "<value>" } }',
          description: "a sub-field with value",
          example: '{ "inner": { "foo": "bar" } }',
          exampleDescription: "Keep events with sub field foo at value bar",
        },
        {
          expression: '{ "<key>": "<number>" }',
          description: "a field with the specific value as number",
          example: '{ "status": 200 }',
          exampleDescription:
            "Keep events with status code at 200 (as number check)",
        },
        {
          expression: '{ "<key>": { "$gt": "<number>" } }',
          description: "a field with number value greater than number",
          example: '{ "status": { "$gt": 100 } }',
          exampleDescription: "Keep events with status code greater than 100",
        },
        {
          expression: '{ "<key>": { "$gte": "<number>" } }',
          description: "a field with number value greater or equal to number",
          example: '{ "status": { "$gte": 100 } }',
          exampleDescription:
            "Keep events with status code greater or equal to 100",
        },
        {
          expression: '{ "<key>": { "$lt": "<number>" } }',
          description: "a field with number value lower than number",
          example: '{ "status": { "$lt": 100 } }',
          exampleDescription: "Keep events with status code lower than 100",
        },
        {
          expression: '{ "<key>": { "$lte": "<number>" } }',
          description: "a field with number value lower or equal to number",
          example: '{ "status": { "$lte": 100 } }',
          exampleDescription:
            "Keep events with status code lower or equal to 100",
        },
        {
          expression:
            '{ "<key>": { "$between": { "min": "<number>", "max": "<number>" } } }',
          description: "a field with value between two values (exclusive)",
          example: '{ "status": { "$between": { "min": 100, "max": 200 } } }',
          exampleDescription:
            "Keep events with status code between 100 and 200 (100 and 200 won't match)",
        },
        {
          expression:
            '{ "<key>": { "$between": { "min": "<number>", "max": "<number>" } } }',
          description: "a field with value between two values (inclusive)",
          example: '{ "status": { "$between": { "min": 100, "max": 200 } } }',
          exampleDescription:
            "Keep events with status code between 100 and 200 (100 and 200 will match)",
        },
        {
          expression:
            '{ "<key>": { "$and": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] } }',
          description: "an object with two fields with values",
          example:
            '{ "inner": { "$and": [ { "foo": "bar" }, { "bar" : "foo" }] } }',
          exampleDescription: "Keep events matching the list of key-value",
        },
        {
          expression:
            '{ "$or": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] }',
          description: "an object matching at least one condition of the list",
          example:
            '{ "$or": [ { "method": "DELETE" }, { "protocol" : "http" }] }',
          exampleDescription:
            "Keep event whose method is http OR method is DELETE OR both",
        },
        {
          expression:
            '{ "$nor": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] }',
          description: "an object that matches no conditions of the list",
          example:
            '{ "$nor": [ { "method": "DELETE" }, { "protocol" : "http" }] }',
          exampleDescription:
            "Keep events whose method is not DELETE AND protocol is not http",
        },
        {
          expression: '{ "<key>": ["<value>", "<value2>"] }',
          description: "an array field with values",
          example: '{ "codes": ["a", "b"] }',
          exampleDescription:
            "Keep events with an array codes which strictly containing values a and b",
        },
        {
          expression: '{ "<key>": { "$contains": "<value>" } }',
          description: "an array field containing a specific value",
          example: '{ "codes": { "$contains": "a" } }',
          exampleDescription:
            "Keep events with an array codes containing an a value",
        },
        {
          expression:
            '{ "<key>": { "$contains": { "key": "<subkey>", "value": "<value>" } } }',
          description: "an object containing a key subkey with given value",
          example:
            '{ "target": { "$contains": { "key": "scheme", "value": "https" } } }',
          exampleDescription:
            "Keep events whose target contains a field 'scheme' valued to 'https' ",
        },
        {
          expression: '{ "<key>": { "$all": ["<value>", "<value>"] } }',
          description: "and array field containing all specific values",
          example: '{ "codes": { "$all": ["a", "b"] } }',
          exampleDescription:
            "Keep events with an array codes containing at minima a and b values",
        },
        {
          expression: '{ "<key>": { "$regex": "<value>" } }',
          description:
            "a string field whose value match given regular expression",
          example: '{ "url": { "$regex": ".*api.*" } }',
          exampleDescription: "Keep events with url containing 'api'",
        },
        {
          expression: '{ "<key>": { "$in": ["<value>", "<value2>"] } }',
          description: "a field containing one of the given value",
          example: '{ "method": { "$in": ["PUT", "POST"] } }',
          exampleDescription: "Keep events whose method is PUT or POST",
        },
        {
          expression: '{ "<key>": { "$nin": ["<value>", "<value2>"] } }',
          description: "a field containing none of the given value",
          example: '{ "method": { "$nin": ["PUT", "POST"] } }',
          exampleDescription:
            "Keep events whose method is neither PUT nor POST",
        },
        {
          expression: '{ "<key>": { "$size": "<number>" } }',
          description: "an array field whose size is given value",
          example: '{ "headers": { "$size": 12 } }',
          exampleDescription: "Keep events with exactly 12 headers",
        },
        {
          expression: '{ "<key>": { "$not": "<condition>" } }',
          description: "an object that does not satisfy condition",
          example: '{ "url": { "$not": { "$regex": ".*api.*" } } }',
          exampleDescription: "Keep events whose url does not contain 'api'",
        },
        {
          expression: '{ "<key>": { "$eq": "<value>" } }',
          description: "a field key with value",
          example: '{ "foo": { "$eq": "bar" } }',
          exampleDescription: "Keep events with foo as key and bar as value",
        },
        {
          expression: '{ "<key>": { "$ne": "<value>" } }',
          description: "a field key whose value is not provided value",
          example: '{ "foo": { "$ne": "bar" } }',
          exampleDescription: "Keep events with foo field not equal to bar",
        },
        {
          expression: '{ "<key>": { "$exists": "<entry>" } }',
          description: "an object field containing given entry as key",
          example: '{ "target": { "$exists": "scheme" } }',
          exampleDescription:
            "Keep events whose target object contains a schema field",
        },
      ];

      let content = "";
      filtering.forEach(
        ({ example, exampleDescription, description, expression }) => {
          content += `
          <div style="display: flex; flex-direction: row; margin-bottom: 1rem;">
            <div style="width: 49%; margin-right: 1%;">
              ${formatCode(expression)}
              <span>${description}</span>
            </div>
            <div style="width: 49%; margin-left: 1%;">
              ${formatCode(example)}
              <span class="editor-value">${exampleDescription}</span>
            </div>
          </div>
        `;
        }
      );

      return `
        <div style="display: flex; flex-direction: column; background: linear-gradient(to right, #eee 49.5%, #303740 49.5%); padding: 12px">
          ${content}
        </div>
      `;
    }

    function generateProjectionContent() {
      const projection = [
        {
          name: `"<field>": true`,
          doc: `{"<field>": true}`,
          description: "Include given field in result.",
          expression: '{"foo": true}',
          data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}], "foo": 1}',
          result: '{"foo": 1}',
        },
        {
          name: "$at",
          doc: `{"<target>": {"$at": "<location>"}}`,
          description: "Values <target> with value <at> location",
          expression: '{"h1": {"$at": "headers.0.value"}}',
          data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}]}',
          result: '{"h1": "1"}',
        },
        {
          name: "$atIf",
          doc: `{"<target>": {"$atIf": {"path": "<path>", "predicate": {"at": "<at>", "value": "<value>"}}}}`,
          description:
            "Put <path> value in <target> if value at <at> match <value>",
          expression: `{"r": {
                            "$atIf": {
                              "path": "hs.0.value",
                              "predicate": {
                                "at": "scheme",
                                "value": "HTTPS"
                              }
                            }
                          }
                        }`,
          data: '{"hs": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}], "scheme": "HTTPS"}',
          result: '{"r": "1"}',
        },
        {
          name: "$pointer",
          doc: `{"<target>": {"$pointer": "<jsonPointer>"}}`,
          description: `Allow to get a json value using JSON pointer spec`,
          expression: `{
                          "h1": {
                            "$pointer": "/headers/0/value"
                          }
                        }`,
          data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}]}',
          result: '{"h1": "1"}',
        },
        {
          name: "$pointerIf",
          doc: `{"<target>": {"$pointerIf": {"path": "<path>", "predicate": {"pointer": "<pointer>", "value": "<value>"}}}}`,
          description: `Put value at <path> in <target> field if and only if value at <pointer> equals <value>. <path> and <pointer> fields are resolved using json pointer spec.`,
          expression: `{
                          "h1": {
                            "$pointerIf": {
                              "path": "/headers/0/value",
                              "predicate": {
                                "pointer": "/scheme",
                                "value": "HTTP"
                              }
                            }
                          }
                        }`,
          data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}], "scheme": "HTTP"}',
          result: '{"h1": "1"}',
        },
        {
          name: "$path",
          doc: `{"<target>": {"$path": "<jsonPath>"}}`,
          description: `Put value located at <jsonPath> in <target>. <jsonPath> is resolved using json path specification.`,
          expression: `{
                         "hs": {
                           "$path": "$.headers[1:]"
                         }
                       }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      },
                      {
                        "key": "k2",
                        "value": "v2"
                      },
                      {
                        "key": "k3",
                        "value": "v3"
                      }
                    ]
                  }`,
          result: `{
                      "hs": [
                        {
                          "key": "k2",
                          "value": "v2"
                        },
                        {
                          "key": "k3",
                          "value": "v3"
                        }
                      ]
                    }`,
        },
        {
          name: "$pathIf",
          doc: `{"<target>": {"$pathIf": {"path": "<jsonPath>", "predicate": {"path": "<predicateJsonPath>", "value": "<value>"}}}}`,
          description: `Put value located at <jsonPath> in <target>, if and only if value at <predicateJsonPath> equals <value>.`,
          expression: `{
                          "test": {
                            "$pathIf": {
                              "path": "$.headers[1:]",
                              "predicate": {
                                "path": "$.scheme",
                                "value": "HTTPS"
                              }
                            }
                          }
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      },
                      {
                        "key": "k2",
                        "value": "v2"
                      },
                      {
                        "key": "k3",
                        "value": "v3"
                      }
                    ],
                    "scheme": "HTTPS"
                  }`,
          result: `{
                      "test": [
                        {
                          "key": "k2",
                          "value": "v2"
                        },
                        {
                          "key": "k3",
                          "value": "v3"
                        }
                      ]
                    }`,
        },
        {
          name: "$compose (array result)",
          doc: `{"<target>": {"$compose": [{"<subtarget>": {"$path": "<path>"}}]}}`,
          description: `Build an array in <target> field, by composing severals operators : $path, $at, $pointer.`,
          expression: `{
                          "result": {
                            "$compose": [
                              {
                                "values": {
                                  "$path": "$.headers[0:].value"
                                }
                              },
                              {
                                "keys": {
                                  "$path": "$.headers[0:].key"
                                }
                              }
                            ]
                          }
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      },
                      {
                        "key": "k2",
                        "value": "v2"
                      },
                      {
                        "key": "k3",
                        "value": "v3"
                      }
                    ],
                    "scheme": "HTTPS"
                  }`,
          result: `{
                      "result": [
                        {
                          "values": [
                            "v1",
                            "v2",
                            "v3"
                          ]
                        },
                        {
                          "keys": [
                            "k1",
                            "k2",
                            "k3"
                          ]
                        }
                      ]
                    }`,
        },
        {
          name: "$compose (object result)",
          doc: `{
                  "<target>": {
                    "$compose": {
                      "<subtarget>": {
                        "$path": "<path>"
                      }
                    }
                  }
                }`,
          description: `Build an object in <target> field, by composing severals operators : $path, $at, $pointer.`,
          expression: `{
                          "result": {
                            "$compose": {
                              "values": {
                                "$path": "$.headers[0:].value"
                              },
                              "keys": {
                                "$path": "$.headers[0:].key"
                              }
                            }
                          }
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      },
                      {
                        "key": "k2",
                        "value": "v2"
                      },
                      {
                        "key": "k3",
                        "value": "v3"
                      }
                    ]
                  }`,
          result: `{
                      "result": {
                        "values": [
                          "v1",
                          "v2",
                          "v3"
                        ],
                        "keys": [
                          "k1",
                          "k2",
                          "k3"
                        ]
                      }
                    }`,
        },
        {
          name: "$value",
          doc: `{
                  "<target>": {
                    "$value": "<value>"
                  }
                }`,
          description: `Put <value> in <target> field. <value> can be either a primtive type, an object or an array.`,
          expression: `{
                          "headers": {
                            "$value": []
                          }
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      },
                      {
                        "key": "k2",
                        "value": "v2"
                      },
                      {
                        "key": "k3",
                        "value": "v3"
                      }
                    ]
                  }`,
          result: `{
                      "headers": []
                    }`,
        },
        {
          name: "$spread",
          doc: `{
                  "$spread": true
                }`,
          description: `Include all properties of source object, this can be used as base for futher transformation with below operators/`,
          expression: `{
                          "$spread": true
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      }
                    ],
                    "scheme": "HTTPS",
                    "foo": 1
                  }`,
          result: `{
                      "headers": [
                        {
                          "key": "k1",
                          "value": "v1"
                        }
                      ],
                      "scheme": "HTTPS",
                      "foo": 1
                    }`,
        },
        {
          name: `{"<field>": false}`,
          doc: `{
                  "$spread": true,
                  "<field>": false
                }`,
          description: `Exclude <field> field from resulting object`,
          expression: `{
                          "$spread": true,
                          "foo": false
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      }
                    ],
                    "scheme": "HTTPS",
                    "foo": 1
                  }`,
          result: `{
                      "headers": [
                        {
                          "key": "k1",
                          "value": "v1"
                        }
                      ],
                      "scheme": "HTTPS"
                    }`,
        },
        {
          name: `$remove`,
          doc: `{
                  "$spread": true,
                  "<field>": {
                    "$remove": true
                  }
                }`,
          description: `Exclude <field> field from resulting object, like for {"<field>": false}`,
          expression: `{
                          "$spread": true,
                          "foo": {
                            "$remove": true
                          }
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "k1",
                        "value": "v1"
                      }
                    ],
                    "scheme": "HTTPS",
                    "foo": 1
                  }`,
          result: `{
                      "headers": [
                        {
                          "key": "k1",
                          "value": "v1"
                        }
                      ],
                      "scheme": "HTTPS"
                    }`,
        },
        {
          name: `$header`,
          doc: `{
                  "<target>": {
                    "$header": {
                      "path": "<path>",
                      "name": "<name>"
                    }
                  }
                }`,
          description: `Put specified header value in <target>. <path> indicate an array of key/value headers, <name> indicate the name of the header.`,
          expression: `{
                          "hostValue": {
                            "$header": {
                              "path": "headers",
                              "name": "Host"
                            }
                          }
                        }`,
          data: `{
                    "headers": [
                      {
                        "key": "Host",
                        "value": "otoroshi.oto.tools:9999"
                      },
                      {
                        "key": "Accept",
                        "value": "application/json"
                      }
                    ]
                  }`,
          result: `{
                      "hostValue": "otoroshi.oto.tools:9999"
                    }`,
        },
        {
          name: `$includeAllKeysMatching`,
          doc: `{
            "$spread": true,
            "<subname>": {
              "$includeAllKeysMatching": [
                "<expression>"
              ]
            }
          }`,
          description: `Filter an object entries based on StartsWith, Wildcard or Regex expression. This filter must be used in a sub-object whose name <subname> doesn't matter, since it won't appear in resulting projection.
          This operator is an array, which mean you can have several filters. A logical OR will be applied between filters, therefore an entry just need to match one filter to be kept.`,
          expression: `{
                          "$spread": true,
                          "_": {
                            "$includeAllKeysMatching": [
                              "Wildcard(f*)",
                              "StartsWith(fi)",
                              "bar",
                              "Regex(.*lo)"
                            ]
                          }
                        }`,
          data: `{
                    "foo": 1,
                    "foobar": 2,
                    "bar": 3,
                    "baz": 4,
                    "hello": "world",
                    "fifou": "test"
                  }`,
          result: `{
                      "foo": 1,
                      "foobar": 2,
                      "bar": 3,
                      "hello": "world",
                      "fifou": "test"
                    }`,
        },
        {
          name: `$excludeAllKeysMatching`,
          doc: `{
            "$spread": true,
            "<subname>": {
              "$excludeAllKeysMatching": [
                "<expression>"
              ]
            }
          }`,
          description: `Filter an object entries, excluding them based on StartsWith, Wildcard or Regex expression. This filter must be used in a sub-object whose name <subname> doesn't matter, since it won't appear in resulting projection.
          This operator is an array, which mean you can have several filters. A logical OR will be applied between filters, therefore an entry just need to match one filter to be deleted.`,
          expression: `{
                          "$spread": true,
                          "_": {
                            "$excludeAllKeysMatching": [
                              "Wildcard(fo*)",
                              "StartsWith(fi)",
                              "bar",
                              "Regex(.*lo)"
                            ]
                          }
                        }`,
          data: `{
                    "foo": 1,
                    "foobar": 2,
                    "bar": 3,
                    "baz": 4,
                    "hello": "world",
                    "fifou": "test"
                  }`,
          result: `{
                      "baz": 4
                    }`,
        },
        {
          name: `$jq`,
          doc: `{
                  "<target>": {
                    "$jq": "<jqExpression>"
                  }
                }`,
          description: `Fill target field with provided JQ selector`,
          expression: `{
                          "headerKeys": {
                            "$jq": "[.headers[].key]"
                          }
                        }`,
          data: `{
                    "headers": [
                      {"key": "k1", "value": "v1"},
                      {"key": "k2", "value": "v2"},
                    ] 
                  }`,
          result: `{
                      "headerKeys": ["k1", "k2"]
                    }`,
        },
        {
          name: `$jqIf`,
          doc: `{
                  "<target>": {
                    "$jqIf": {
                      "filter": "<jqExpression>",
                      "predicate": {
                        "path": "<path>",
                        "value": "<value>"
                      }
                    }
                  }
                }`,
          description: `Fill target field with provided JQ selector if and only if value located at <path> is equal to <value>`,
          expression: `{
                          "headerKeys": {
                            "$jqIf": {
                              "filter": "[.headers[].key]",
                              "predicate": {
                                "path": "target.scheme",
                                "value": "https"
                              }
                            }
                          }
                        }`,
          data: `{
                    "headers": [
                      {"key": "k1", "value": "v1"},
                      {"key": "k2", "value": "v2"}
                    ],
                    "target": {
                      "scheme": "https"
                    }
                  }`,
          result: `{
                      "headerKeys": ["k1", "k2"]
                    }`,
        },
      ];

      function buildBlock(index) {
        return `
          <h3>${projection[index].name
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")}</h3>
          <div style="display: flex; flex-direction: row;">
              <div style="width: 35%; margin-right: 1%;">
                <h4>Description</h4>
                ${formatCode(projection[index].doc)}
                <p>${projection[index].description
                  .replaceAll("<", "&lt;")
                  .replaceAll(">", "&gt;")}</p>
              </div>
              <div style="margin-left: 1%; width: 63%;">
                <div style="display: flex; flex-direction: row;">
                  <div style="width: 49%">
                    <h4 style="color: #fff;">Expression</h4>
                    ${formatCode(projection[index].expression)}
                  </div>
                  <div style="width: 49%; margin-left: 2%;">
                    <h4 style="color: #fff;">Event</h4>
                    ${formatCode(projection[index].data)}
                  </div>
                </div>
                <div style="margin-top: 8px; width: 100%; display: flex; flex-direction: column; justify-content: center; align-items: center;">
                  <h4 style="color: #fff;">Result</h4>
                  ${formatCode(projection[index].result)}
                </div>
              </div>
            </div>
          `;
      }

      let html = "";
      for (let i = 0; i < projection.length; i++) {
        html += buildBlock(i);
      }

      return `
          <div style="background: linear-gradient(to right, rgb(238, 238, 238) 36.25%, rgb(48, 55, 64) 36.25%); padding: 12px;">
            ${html}
          </div>
        `;
    }

    function formatCode(code) {
      let res = "";
      try {
        res = JSON.stringify(JSON.parse(code), null, 1);
      } catch {
        console.log("failed to format", code);
        res = code;
      }
      res = res.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
      return `<pre style="width: 100%; border: none !important; background-color: #ddd !important; border-radius: 4px!important; padding: 0.5rem !important;"><code style="width: fit-content;">${res}</code></pre>`;
    }

    document.getElementById("filtering").innerHTML = generateFilteringContent();
    document.getElementById("projection").innerHTML =
      generateProjectionContent();
  }
});
