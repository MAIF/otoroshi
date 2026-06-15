import React from 'react';

const expressions = [
  {
    name: "Date information",
    editor: [
      { expression: "${date}", description: "the date corresponding to the moment where the request passed. The field is in Date Time format, with default format *yyy-mm-ddTHH:MM:SS.sss*" },
      { expression: "${date.format('<format>')}", description: "same field that the previous but with a chosen format." },
      { expression: "${date_el(req.header.date).format('<format>')}", description: "get the date value from an el expression and formats it." },
      { expression: "${date_el(req.header.date).format('<format>')}", description: "get the date value from an el expression and formats it." },
    ],
    values: [
      { expression: "${date}", value: "2021-11-25T10:53:25.366+01:00" },
      { expression: "${date.format('yyyy-MM-dd')}", value: "2021-11-25" },
      { expression: "${date_el(req.header.date).epoch_ms", value: "1747816667560" },
    ],
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
      { expression: "${service.domain}", value: "myservice.oto.tools" },
      { expression: "${service.subdomain}", value: "myservice" },
      { expression: "${service.tld}", value: "oto.tools" },
      { expression: "${service.env}", value: "prod" },
      { expression: "${service.id}", value: "GnzxRRWi..." },
      { expression: "${service.name}", value: "service" },
      { expression: "${service.groups[0:'unkown group']}", value: "unknown-group" },
      { expression: "${service.groups[0]}", value: "default-group" },
      { expression: "${service.metadata.test:'default-value'}", value: "default-value" },
      { expression: "${service.metadata.foo}", value: "bar" },
    ],
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
      { expression: "${req.query.<query param>:'<default value>'}", description: "get specific query param of the request" },
      { expression: "${req.query.<query param>}", description: "get specific query param of the request or get `no-path-param-<path param>` as value" },
      { expression: "${req.pathparams.<path param>:'<default value>'}", description: "get specific path param of the request" },
      { expression: "${req.pathparams.<path param>}", description: "get specific path param of the request or get `no-path-param-<path param>` as value" },
    ],
    values: [
      { expression: "${req.fullUrl}", value: "http://api.oto.tools:8080/api/?foo=bar" },
      { expression: "${req.path}", value: "/api/" },
      { expression: "${req.uri}", value: "/api/?foo=bar" },
      { expression: "${req.host}", value: "api.oto.tools:8080" },
      { expression: "${req.domain}", value: "api.oto.tools" },
      { expression: "${req.method}", value: "GET" },
      { expression: "${req.protocol}", value: "http" },
      { expression: "${req.headers.foob:default value>", value: "default value" },
      { expression: "${req.headers.foo}", value: "bar" },
      { expression: "${req.query.foob:default value}", value: "default value" },
      { expression: "${req.query.foo}", value: "bar" },
      { expression: "${req.pathparams.foob:'default value'}", value: "default value" },
      { expression: "${req.pathparams.foo}", value: "bar" },
    ],
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
      { expression: "${apikey.name}", value: "Otoroshi Backoffice ApiKey" },
      { expression: "${apikey.id}", value: "admin-api-apikey-id" },
      { expression: "${apikey.metadata.myfield:'default value'}", value: "default value" },
      { expression: "${apikey.metadata.foo}", value: "bar" },
      { expression: "${apikey.tags['0':'no-found-tag']}", value: "no-found-tag" },
      { expression: "${apikey.tags['0']}", value: "one-tag" },
    ],
  },
  {
    name: "Token information",
    context: "Only on jwt verifier fields",
    editor: [
      { expression: "${token.<field>.replace('<a>','<b>')}", description: "get token field and replace a value by b or get `no-token-<field>` as value" },
      { expression: "${token.<field>.replaceAll('<a>','<b>')}", description: "get token field and replace all a value by b or get `no-token-<field>` as value" },
      { expression: "${token.<field>|token.<field-2>:<default value>}", description: "get claim of the token, or the second claim of the token or a default value if not present" },
      { expression: "${token.<field>|token.<field-2>}", description: "get claim of the token or `no-token-$field-$field2` if not present" },
      { expression: "${token.<field>:<default value>}", description: "get claim of the token or a default value if not present" },
      { expression: "${token.<field>}", description: "get claim of the token" },
    ],
    values: [
      { expression: "${token.foo.replace('o','a')}", value: "fao" },
      { expression: "${token.foo.replaceAll('o','a')}", value: "faa" },
      { expression: "${token.foob|token.foob2:'not-found'}", value: "not-found" },
      { expression: "${token.foob|token.foo}", value: "foo" },
      { expression: "${token.foob:'not-found-foob'}", value: "not-found-foob" },
      { expression: "${token.foo}", value: "bar" },
    ],
  },
  {
    name: "System Environment information",
    editor: [
      { expression: "${env.<field>:<default value>}", description: "get system environment variable or a default value if not present" },
      { expression: "${env.<field>}", description: "get system environment variable or `no-env-var-<field>` if not present" },
    ],
    values: [
      { expression: "${env.java_h:'not-found-java_h'}", value: "not-found-java_h" },
      { expression: "${env.PATH}", value: "/usr/local/bin:" },
    ],
  },
  {
    name: "Environment information",
    editor: [
      { expression: "${config.<field>:<default value>}", description: "get environment variable or a default value if not present" },
      { expression: "${config.<field>}", description: "get environment variable or `no-config-<field>` if not present" },
    ],
    values: [
      { expression: "${config.http.ports:'not-found'}", value: "not-found" },
      { expression: "${config.http.port}", value: "8080" },
    ],
  },
  {
    name: "Context information",
    editor: [
      { expression: "${ctx.<field>.replace('<a>','<b>')}", description: "get field and replace a value by b in the string, or set `no-ctx-<field>` as value" },
      { expression: "${ctx.<field>.replaceAll('<a>','<b>')}", description: "get field and replace all a value by b in the string, or set `no-ctx-<field>` as value" },
      { expression: "${ctx.<field>|ctx.<field-2>:<default value>}", description: "get field or if empty the second field, or the set the default value" },
      { expression: "${ctx.<field>|ctx.<field-2>}", description: "get field or if empty the second field, or the set `no-ctx-<field>-<field2>` as value" },
      { expression: "${ctx.<field>:<default value>}", description: "get field or the set the default value" },
      { expression: "${ctx.<field>}", description: "get field or the set `no-ctx-<field>` as value" },
      { expression: "${ctx.useragent.<field>}", description: "get user agent field or set `no-ctx-<field>` as value" },
      { expression: "${ctx.geolocation.<field>}", description: "get geolocation field or set `no-ctx-<field>` as value" },
    ],
    values: [
      { expression: "${ctx.foo.replace('o','a')}", value: "fao" },
      { expression: "${ctx.foo.replaceAll('o','a')}", value: "faa" },
      { expression: "${ctx.foob|ctx.foot:'not-found'}", value: "not-found" },
      { expression: "${ctx.foob|ctx.foo}", value: "bar" },
      { expression: "${ctx.foob:'other'}", value: "other" },
      { expression: "${ctx.foo}", value: "bar" },
      { expression: "${ctx.useragent.foo}", value: "no-ctx-foo" },
      { expression: "${ctx.geolocation.foo}", value: "no-ctx-foo" },
    ],
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
      { expression: "${user.profile.<field>}", description: "get field of profile of user or `no-profile-<field>` as value" },
    ],
    values: [
      { expression: "${user.name}", value: "Otoroshi Admin" },
      { expression: "${user.email}", value: "admin@otoroshi.io" },
      { expression: "${user.metadata.username:'not-found'}", value: "not-found" },
      { expression: "${user.metadata.username}", value: "no-meta-username" },
      { expression: "${user.profile.username:'not-found'}", value: "not-found" },
      { expression: "${user.profile.name}", value: "Otoroshi Admin" },
    ],
  },
];

function ExpressionSection({ item }) {
  return (
    <div className="el-section">
      <div className="el-editor">
        <h3>{item.name}</h3>
        {item.context && <span className="el-context">{item.context}</span>}
        {item.editor.map((entry, i) => (
          <div key={i} className="el-entry">
            <code>{entry.expression}</code>
            <span className="el-description">{entry.description}</span>
          </div>
        ))}
      </div>
      <div className="el-values">
        <div className="el-values-title"><span>{item.name}</span></div>
        {item.values.map((entry, i) => (
          <div key={i} className="el-value-entry">
            <code>{entry.expression}</code>
            <span className="el-value">{entry.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export default function ExpressionLanguage() {
  return (
    <div className="el-container">
      {expressions.map((item, i) => (
        <ExpressionSection key={i} item={item} />
      ))}
    </div>
  );
}
