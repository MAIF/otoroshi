
document.addEventListener("DOMContentLoaded", function () {

  if (document.getElementById("expression-language")) {
    const expressions = [
      {
        name: "Date information",
        editor: [
          { expression: "${date}", description: "the date corresponding to the moment where the request passed. The field is in Date Time format, with default format *yyy-mm-ddTHH:MM:SS.sss*" },
          { expression: "${date.format('<format>')}", description: "same field that the previous but with a chosen format." }
        ],
        values: [
          { expression: "${date}", description: "2021-11-25T10:53:25.366+01:00" },
          { expression: "${date.format('yyy-MM-dd')}", description: "2021-11-25" }
        ]
      },
      {
        name: "Service information",
        editor: [
          { expression: "${service.domain}", description: "matched service domain by the query" },
          { expression: "${service.subdomain}", description: "matched service subdomain by the query" },
          { expression: "${service.tld}", description: "matched top level domain by the query" },
          { expression: "${service.env}", description: "Otoroshi environment of the matched service" },
          { expression: "${service.id}", description: "matched service id" },
          { expression: "${service.name}", description: "matched service name" },
          { expression: "${service.groups[<field>:<default value>]}", description: "get nth group of the service or returned the default value" },
          { expression: "${service.groups[<field>]}", description: "get nth group of the service or returned `no-group-<field>` as value" },
          { expression: "${service.metadata.<field>:<default value>}", description: "get the metadata of the service or returned the default value" },
          { expression: "${service.metadata.<field>}", description: "get the metadata of the service or returned `no-meta-<field>` as value" },
        ],
        values: [
          { expression: "${service.domain}", description: "myservice.oto.tools" },
          { expression: "${service.subdomain}", description: "myservice" },
          { expression: "${service.tld}", description: "oto.tools" },
          { expression: "${service.env}", description: "prod" },
          { expression: "${service.id}", description: "GnzxRRWi..." },
          { expression: "${service.name}", description: "service" },
          { expression: "${service.groups[0:'unkown group']}", description: "unknown-group" },
          { expression: "${service.groups[0]}", description: "default-group" },
          { expression: "${service.metadata.test:'default-value'}", description: "default-value" },
          { expression: "${service.metadata.foo}", description: "bar" },
        ]
      },
      {
        name: "Request information",
        editor: [
          { expression: "${req.fullUrl}", description: "the complete URL with protocol, host and relative URI" },
          { expression: "${req.path}", description: "the path of the request" },
          { expression: "${req.uri}", description: "the relative URI" },
          { expression: "${req.host}", description: "the host of the request" },
          { expression: "${req.domain}", description: "the domain of the request" },
          { expression: "${req.method}", description: "the method of the request" },
          { expression: "${req.protocol}", description: "the protocol of the request" },
          { expression: "${req.headers.<header>:'<default value>'}", description: "get specific header of the request" },
          { expression: "${req.headers.<header>}", description: "get specific header of the request or get `no-header-<header>` as value" },
          { expression: "${req.query.<query param>:'<default value>}", description: "get specific query param of the request" },
          { expression: "${req.query.<query param>}", description: "get specific query param of the request or get `no-query-<query param>` as value" },
        ],
        values: [
          { expression: "${req.fullUrl}", description: "http://api.oto.tools:8080/api/?foo=bar" },
          { expression: "${req.path}", description: "/api/" },
          { expression: "${req.uri}", description: "/api/?foo=bar" },
          { expression: "${req.host}", description: "api.oto.tools:8080" },
          { expression: "${req.domain}", description: "api.oto.tools" },
          { expression: "${req.method}", description: "GET" },
          { expression: "${req.protocol}", description: "http" },
          { expression: "${req.headers.foob:default value>", description: "default value" },
          { expression: "${req.headers.foo}", description: "bar" },
          { expression: "${req.query.foob:default value}", description: "default value" },
          { expression: "${req.query.foo}", description: "bar" },
        ]
      },
      {
        name: "Apikey information",
        editor: [
          { expression: "${apikey.name}", description: "if apikey is present, the client name of the apikey" },
          { expression: "${apikey.id}", description: "if apikey is present in the request, the client id of the apikey" },
          { expression: "${apikey.metadata.<field>:'<default value>'}", description: "if apikey is present, got the expected metadata, else got the default value" },
          { expression: "${apikey.metadata.<field>}", description: "if apikey is present, got the expected metadata, else got the default value `no-meta-<field>`" },
          { expression: "${apikey.tags[<field>:'<default value>']}", description: "if apikey is present, got the nth tags or the default value" },
          { expression: "${apikey.tags[<field>]}", description: "if apikey is present, got the nth tags or `no-tag-<field>` as value" },
        ],
        values: [
          { expression: "${apikey.name}", description: "Otoroshi Backoffice ApiKey" },
          { expression: "${apikey.id}", description: "admin-api-apikey-id" },
          { expression: "${apikey.metadata.myfield:'default value'}", description: "default value" },
          { expression: "${apikey.metadata.foo}", description: "bar" },
          { expression: "${apikey.tags['0':'no-found-tag']}", description: "no-found-tag" },
          { expression: "${apikey.tags['0']}", description: "one-tag" },
        ]
      },
      {
        name: "Token information",
        context: "Only on jwt verifier fields",
        editor: [
          { expression: "${token.<field>.replace('<a>','<b>')}", description: "get token field and replace a value by b or get `no-token-<field>` as value" },
          { expression: "${token.<field>.replaceAll('<a>','<b>')}", description: "get token field and replace **all** a value by b or get `no-token-<field>` as value" },
          { expression: "${token.<field>|token.<field-2>:<default value>}", description: "get claim of the token, ot the second claim of the token or a default value if not present" },
          { expression: "${token.<field>|token.<field-2>}", description: "get claim of the token or `no-token-$field-$field2` if not present" },
          { expression: "${token.<field>:<default value>}", description: "get claim of the token or a default value if not present" },
          { expression: "${token.<field>}", description: "get claim of the token" },
        ],
        values: [
          { expression: "${token.foo.replace('o','a')}", description: "fao" },
          { expression: "${token.foo.replaceAll('o','a')}", description: "faa" },
          { expression: "${token.foob|token.foob2:'not-found'}", description: "not-found" },
          { expression: "${token.foob|token.foo}", description: "foo" },
          { expression: "${token.foob:'not-found-foob'}", description: "not-found-foob" },
          { expression: "${token.foo}", description: "bar" },
        ]
      },
      {
        name: "System Environment information",
        editor: [
          { expression: "${env.<field>:<default value>}", description: "get system environment variable or a default value if not present" },
          { expression: "${env.<field>}", description: "get system environment variable or `no-env-var-<field>` if not present" },
        ],
        values: [
          { expression: "${env.java_h:'not-found-java_h'}", description: "not-found-java_h" },
          { expression: "${env.PATH}", description: "/usr/local/bin:" },
        ]
      },
      {
        name: "Environment information",
        editor: [
          { expression: "${config.<field>:<default value>}", description: "get environment variable or a default value if not present" },
          { expression: "${config.<field>}", description: "get environment variable or `no-config-<field>` if not present" },
        ],
        values: [
          { expression: "${config.http.ports:'not-found'}", description: "not-found" },
          { expression: "${config.http.port}", description: "8080" },
        ]
      },
      {
        name: "Context information",
        editor: [
          { expression: "${ctx.<field>.replace('<a>','<b>')}", description: "get field and replace a value  by b in the string, or set `no-ctx-<field>` as value" },
          { expression: "${ctx.<field>.replaceAll('<a>','<b>')}", description: "get field and replace all a value by b in the string, or set `no-ctx-<field>` as value" },
          { expression: "${ctx.<field>|ctx.<field-2>:<default value>}", description: "get field or if empty the second field, or the set the default value" },
          { expression: "${ctx.<field>|ctx.<field-2>}", description: "get field or if empty the second field, or the set `no-ctx-<field>-<field2>` as value" },
          { expression: "${ctx.<field>:<default value>}", description: "get field or the set the default value" },
          { expression: "${ctx.<field>}", description: "get field or the set `no-ctx-<field>` as value" },
          { expression: "${ctx.useragent.<field>}", description: "get user agent field or set `no-ctx-<field>` as value" },
          { expression: "${ctx.geolocation.<field>}", description: "get geolocation field or set `no-ctx-<field>` as value" },
        ],
        values: [
          { expression: "${ctx.foo.replace('o','a')}", description: "fao" },
          { expression: "${ctx.foo.replaceAll('o','a')}", description: "faa" },
          { expression: "${ctx.foob|ctx.foot:'not-found'}", description: "not-found" },
          { expression: "${ctx.foob|ctx.foo}", description: "bar" },
          { expression: "${ctx.foob:'other'}", description: "other" },
          { expression: "${ctx.foo}", description: "bar" },
          { expression: "${ctx.useragent.foo}", description: "no-ctx-foo" },
          { expression: "${ctx.geolocation.foo}", description: "no-ctx-foo" },
        ]
      },
      {
        name: "User information",
        context: "If call to a private app",
        editor: [
          { expression: "${user.name}", description: "get user name" },
          { expression: "${user.email}", description: "get user email" },
          { expression: "${user.metadata.<field>:<default value>}", description: "get metadata of user or get the default value" },
          { expression: "${user.metadata.<field>}", description: "get metadata of user or `no-meta-<field>` as value" },
          { expression: "${user.profile.<field>:<default value>}", description: "get field of profile of user or get the default value" },
          { expression: "${user.profile.<field>}", description: " get field of profile of user or `no-profile-<field>` as value" },
        ],
        values: [
          { expression: "${user.name}", description: "Otoroshi Admin" },
          { expression: "${user.email}", description: "admin@otoroshi.io" },
          { expression: "${user.metadata.username:'not-found'}", description: "not-found" },
          { expression: "${user.metadata.username}", description: "no-meta-username" },
          { expression: "${user.profile.username:'not-found'}", description: "not-found" },
          { expression: "${user.profile.name}", description: "Otoroshi Admin" },
        ],
      }
    ]

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
      })

      const valuesContent = document.createElement("div");

      valuesContent.appendChild(Example(item.name));

      item.values.forEach(({ expression, description }) => {
        const p = document.createElement("p");

        const code = document.createElement("code");
        code.textContent = expression;

        const span = document.createElement("span");
        span.classList.add("editor-value")
        span.textContent = description;

        p.appendChild(code);
        p.appendChild(span);

        valuesContent.appendChild(p);
      });

      editor.appendChild(editorContent);
      editor.appendChild(valuesContent);


      container.appendChild(editor);
      return container
    }

    function Example(name) {
      const div = document.createElement("div");
      div.classList.add("editor-title");

      const span = document.createElement("span");
      span.textContent = name;

      div.appendChild(span);
      return div;
    }

    expressions.forEach(item => {
      container.appendChild(createBloc(item));
    })

    document.getElementById("expressions").appendChild(container);

    container.style.background = "linear-gradient(to right, #eee 60%, #303740 40%)"
    container.style.padding = "12px"
  }

  else if (document.getElementById("data-exporters")) {
    const filtering = [
      {
        name: "Keep events with",
        editor: [
          { expression: '{ "<key>": "<value>" }', description: "a field key with value" },
          { expression: '{ "<key>": { "$wildcard": "<value>*" } }', description: "a field starting with value" },
          { expression: '{ "<key>": { "<key2>": "<value>" } }', description: "a sub-field with value" },
          { expression: '{ "<key>": <number> }', description: "a field with the specific value as number" },
          { expression: '{ "<key>": { "$gt": <number> } }', description: "a field with number value greater than number" },
          { expression: '{ "<key>": { "$gte": <number> } }', description: "a field with number value greater or equal to number" },
          { expression: '{ "<key>": { "$lt": <number> } }', description: "a field with number value lower than number" },
          { expression: '{ "<key>": { "$lte": <number> } }', description: "a field with number value lower or equal to number" },
          { expression: '{ "<key>": { "$between": { "min": <number>, "max": <number> } } }', description: "a field with value between two values" },
          { expression: '{ "<key>": { "$and": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] } }', description: "an object with two fields with values" },
          { expression: '{ "<key>": { "$or": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] } }', description: "an object with one subfield matching an element of the list" },
          { expression: '{ "<key>": ["<value>", "<value2>"] }', description: "an array field with values" },
          { expression: '{ "<key>": { "contains": "<value>" } }', description: "an array field containing a specific value" },
          { expression: '{ "<key>": { "$all": ["<value>", "<value>"] } }', description: "and array field containing all specific values" }
        ],
        values: [
          { expression: '{ "foo": "bar" }', description: "Keep events with foo as key and bar as value" },
          { expression: '{ "type": { "$wildcard": "Alert*" } }', description: "Keep events with type field starting with Alert" },
          { expression: '{ "inner": { "foo": "bar" } }', description: "Keep events with sub field foo at value bar" },
          { expression: '{ "status": 200 }', description: "Keep events with status code at 200 (as number check)" },
          { expression: '{ "status": { "$gt": 100 } }', description: "Keep events with status code greater than 100" },
          { expression: '{ "status": { "$gte": 100 } }', description: "Keep events with status code greater or equal to 100" },
          { expression: '{ "status": { "$lt": 100 } }', description: "Keep events with status code lower than 100" },
          { expression: '{ "status": { "$lte": 100 } }', description: "Keep events with status code lower or equal to 100" },
          { expression: '{ "status": { "$between": { "min": 100, "max": 200 } } }', description: "Keep events with status code between 100 and 200" },
          { expression: '{ "inner": { "$and": [ { "foo": "bar" }, { "bar" : "foo" }] } }', description: "Keep events matching the list of key-value" },
          { expression: '{ "inner": { "$or": [ { "foo": "bar" }, { "bar" : "foo" }] } }', description: "Keep events matching one condition of the list of key-value" },
          { expression: '{ "codes": ["a", "b"] }', description: "Keep events with an array codes which strictly containing values a and b" },
          { expression: '{ "codes": { "contains": "a" } }', description: "Keep events with an array codes containing an a value" },
          { expression: '{ "codes": { "$all": ["a", "b"] } }', description: "Keep events with an array codes containing at minima a and b values" }
        ]
      }
    ]

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
      })

      const valuesContent = document.createElement("div");

      valuesContent.appendChild(Example(item.name));

      item.values.forEach(({ expression, description }) => {
        const p = document.createElement("p");

        const code = document.createElement("code");
        code.textContent = expression;

        const span = document.createElement("span");
        span.classList.add("editor-value")
        span.textContent = description;

        p.appendChild(code);
        p.appendChild(span);

        valuesContent.appendChild(p);
      });

      editor.appendChild(editorContent);
      editor.appendChild(valuesContent);


      container.appendChild(editor);
      return container
    }

    function Example(name) {
      const div = document.createElement("div");
      div.classList.add("editor-title");

      const span = document.createElement("span");
      span.textContent = name;

      div.appendChild(span);
      return div;
    }

    filtering.forEach(item => {
      container.appendChild(createBloc(item));
    })

    document.getElementById("filtering").appendChild(container);

    container.style.background = "linear-gradient(to right, #eee 60%, #303740 40%)"
    container.style.padding = "12px"
  }
});