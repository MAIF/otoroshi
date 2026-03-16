import React from 'react';

// ==================== FILTERING ====================

const filtering = [
  { expression: '{ "<key>": "<value>" }', description: "a field key with value", example: '{ "foo": "bar" }', exampleDescription: "Keep events with foo as key and bar as value" },
  { expression: '{ "<key>": { "$wildcard": "<value>*" } }', description: "a field starting with value", example: '{ "type": { "$wildcard": "Alert*" } }', exampleDescription: "Keep events with type field starting with Alert" },
  { expression: '{ "<key>": { "<key2>": "<value>" } }', description: "a sub-field with value", example: '{ "inner": { "foo": "bar" } }', exampleDescription: "Keep events with sub field foo at value bar" },
  { expression: '{ "<key>": "<number>" }', description: "a field with the specific value as number", example: '{ "status": 200 }', exampleDescription: "Keep events with status code at 200 (as number check)" },
  { expression: '{ "<key>": { "$gt": "<number>" } }', description: "a field with number value greater than number", example: '{ "status": { "$gt": 100 } }', exampleDescription: "Keep events with status code greater than 100" },
  { expression: '{ "<key>": { "$gte": "<number>" } }', description: "a field with number value greater or equal to number", example: '{ "status": { "$gte": 100 } }', exampleDescription: "Keep events with status code greater or equal to 100" },
  { expression: '{ "<key>": { "$lt": "<number>" } }', description: "a field with number value lower than number", example: '{ "status": { "$lt": 100 } }', exampleDescription: "Keep events with status code lower than 100" },
  { expression: '{ "<key>": { "$lte": "<number>" } }', description: "a field with number value lower or equal to number", example: '{ "status": { "$lte": 100 } }', exampleDescription: "Keep events with status code lower or equal to 100" },
  { expression: '{ "<key>": { "$between": { "min": "<number>", "max": "<number>" } } }', description: "a field with value between two values (exclusive)", example: '{ "status": { "$between": { "min": 100, "max": 200 } } }', exampleDescription: "Keep events with status code between 100 and 200 (100 and 200 won't match)" },
  { expression: '{ "<key>": { "$between": { "min": "<number>", "max": "<number>" } } }', description: "a field with value between two values (inclusive)", example: '{ "status": { "$between": { "min": 100, "max": 200 } } }', exampleDescription: "Keep events with status code between 100 and 200 (100 and 200 will match)" },
  { expression: '{ "<key>": { "$and": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] } }', description: "an object with two fields with values", example: '{ "inner": { "$and": [ { "foo": "bar" }, { "bar" : "foo" }] } }', exampleDescription: "Keep events matching the list of key-value" },
  { expression: '{ "$or": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] }', description: "an object matching at least one condition of the list", example: '{ "$or": [ { "method": "DELETE" }, { "protocol" : "http" }] }', exampleDescription: "Keep event whose method is http OR method is DELETE OR both" },
  { expression: '{ "$nor": [ { "<key2>": "<value>" }, { "<key3>" : "<value>" }] }', description: "an object that matches no conditions of the list", example: '{ "$nor": [ { "method": "DELETE" }, { "protocol" : "http" }] }', exampleDescription: "Keep events whose method is not DELETE AND protocol is not http" },
  { expression: '{ "<key>": ["<value>", "<value2>"] }', description: "an array field with values", example: '{ "codes": ["a", "b"] }', exampleDescription: "Keep events with an array codes which strictly containing values a and b" },
  { expression: '{ "<key>": { "$contains": "<value>" } }', description: "an array field containing a specific value", example: '{ "codes": { "$contains": "a" } }', exampleDescription: "Keep events with an array codes containing an a value" },
  { expression: '{ "<key>": { "$contains": { "key": "<subkey>", "value": "<value>" } } }', description: "an object containing a key subkey with given value", example: '{ "target": { "$contains": { "key": "scheme", "value": "https" } } }', exampleDescription: "Keep events whose target contains a field 'scheme' valued to 'https'" },
  { expression: '{ "<key>": { "$all": ["<value>", "<value>"] } }', description: "and array field containing all specific values", example: '{ "codes": { "$all": ["a", "b"] } }', exampleDescription: "Keep events with an array codes containing at minima a and b values" },
  { expression: '{ "<key>": { "$regex": "<value>" } }', description: "a string field whose value match given regular expression", example: '{ "url": { "$regex": ".*api.*" } }', exampleDescription: "Keep events with url containing 'api'" },
  { expression: '{ "<key>": { "$in": ["<value>", "<value2>"] } }', description: "a field containing one of the given value", example: '{ "method": { "$in": ["PUT", "POST"] } }', exampleDescription: "Keep events whose method is PUT or POST" },
  { expression: '{ "<key>": { "$nin": ["<value>", "<value2>"] } }', description: "a field containing none of the given value", example: '{ "method": { "$nin": ["PUT", "POST"] } }', exampleDescription: "Keep events whose method is neither PUT nor POST" },
  { expression: '{ "<key>": { "$size": "<number>" } }', description: "an array field whose size is given value", example: '{ "headers": { "$size": 12 } }', exampleDescription: "Keep events with exactly 12 headers" },
  { expression: '{ "<key>": { "$not": "<condition>" } }', description: "an object that does not satisfy condition", example: '{ "url": { "$not": { "$regex": ".*api.*" } } }', exampleDescription: "Keep events whose url does not contain 'api'" },
  { expression: '{ "<key>": { "$eq": "<value>" } }', description: "a field key with value", example: '{ "foo": { "$eq": "bar" } }', exampleDescription: "Keep events with foo as key and bar as value" },
  { expression: '{ "<key>": { "$ne": "<value>" } }', description: "a field key whose value is not provided value", example: '{ "foo": { "$ne": "bar" } }', exampleDescription: "Keep events with foo field not equal to bar" },
  { expression: '{ "<key>": { "$exists": "<entry>" } }', description: "an object field containing given entry as key", example: '{ "target": { "$exists": "scheme" } }', exampleDescription: "Keep events whose target object contains a schema field" },
];

function formatJson(str) {
  try { return JSON.stringify(JSON.parse(str), null, 2); } catch { return str; }
}

function FilteringRow({ item }) {
  return (
    <div className="de-filter-row">
      <div className="de-filter-left">
        <pre><code>{formatJson(item.expression)}</code></pre>
        <span className="de-filter-desc">{item.description}</span>
      </div>
      <div className="de-filter-right">
        <pre><code>{formatJson(item.example)}</code></pre>
        <span className="de-filter-example">{item.exampleDescription}</span>
      </div>
    </div>
  );
}

export function FilteringDocs() {
  return (
    <div className="de-filter-container">
      {filtering.map((item, i) => <FilteringRow key={i} item={item} />)}
    </div>
  );
}

// ==================== PROJECTION ====================

const projection = [
  {
    name: '"<field>": true',
    doc: '{"<field>": true}',
    description: "Include given field in result.",
    expression: '{"foo": true}',
    data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}], "foo": 1}',
    result: '{"foo": 1}',
  },
  {
    name: "$date_from_unix_fmt",
    doc: '{"<field>": { "$date_from_unix_fmt": {"path":"<source>", "pattern": "<pattern>"}}}',
    description: "Formats a unix timestamp into a stringified datetime",
    expression: '{"@timestamp": { "$date_from_unix_fmt": {"path":"@timestamp", "pattern": "yyyy-MM-dd\'T\'HH:mm:ss.SSSZ"}}}',
    data: '{"@timestamp": 1764086514458}',
    result: '{"@timestamp": "2025-11-25T17.00.00.000Z"}',
  },
  {
    name: "$at",
    doc: '{"<target>": {"$at": "<location>"}}',
    description: "Values <target> with value <at> location",
    expression: '{"h1": {"$at": "headers.0.value"}}',
    data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}]}',
    result: '{"h1": "1"}',
  },
  {
    name: "$at (with default)",
    doc: '{"<target>": {"$at": { "path":"<location>", "default": "<default_value>"}}}',
    description: "Values <target> with value <at> location",
    expression: '{"h1": {"$at": { "path":"headers.3.value", "default": "foo" }}}',
    data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}]}',
    result: '{"h3": "foo"}',
  },
  {
    name: "$atIf",
    doc: '{"<target>": {"$atIf": {"path": "<path>", "predicate": {"at": "<at>", "value": "<value>"}, "default": "<default_value>"}}}',
    description: "Put <path> value in <target> if value at <at> match <value>",
    expression: '{"r": {"$atIf": {"path": "hs.0.value", "predicate": {"at": "scheme", "value": "HTTPS"}}}}',
    data: '{"hs": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}], "scheme": "HTTPS"}',
    result: '{"r": "1"}',
  },
  {
    name: "$pointer",
    doc: '{"<target>": {"$pointer": "<jsonPointer>"}}',
    description: 'Allow to get a json value using JSON pointer spec. "<jsonPointer>" can be an object with "path" and "default"',
    expression: '{"h1": {"$pointer": "/headers/0/value"}}',
    data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}]}',
    result: '{"h1": "1"}',
  },
  {
    name: "$pointerIf",
    doc: '{"<target>": {"$pointerIf": {"path": "<path>", "predicate": {"pointer": "<pointer>", "value": "<value>"}, "default": "<default_value>"}}}',
    description: "Put value at <path> in <target> field if and only if value at <pointer> equals <value>. <path> and <pointer> fields are resolved using json pointer spec.",
    expression: '{"h1": {"$pointerIf": {"path": "/headers/0/value", "predicate": {"pointer": "/scheme", "value": "HTTP"}}}}',
    data: '{"headers": [{"key": 1, "value": "1"}, {"key": 2, "value": "2"}], "scheme": "HTTP"}',
    result: '{"h1": "1"}',
  },
  {
    name: "$path",
    doc: '{"<target>": {"$path": "<jsonPath>"}}',
    description: 'Put value located at <jsonPath> in <target>. <jsonPath> is resolved using json path specification. "<jsonPath>" can be an object with "path" and "default"',
    expression: '{"hs": {"$path": "$.headers[1:]"}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}, {"key": "k3", "value": "v3"}]}',
    result: '{"hs": [{"key": "k2", "value": "v2"}, {"key": "k3", "value": "v3"}]}',
  },
  {
    name: "$pathIf",
    doc: '{"<target>": {"$pathIf": {"path": "<jsonPath>", "predicate": {"path": "<predicateJsonPath>", "value": "<value>"}, "default": "<default_value>"}}}',
    description: "Put value located at <jsonPath> in <target>, if and only if value at <predicateJsonPath> equals <value>.",
    expression: '{"test": {"$pathIf": {"path": "$.headers[1:]", "predicate": {"path": "$.scheme", "value": "HTTPS"}}}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}, {"key": "k3", "value": "v3"}], "scheme": "HTTPS"}',
    result: '{"test": [{"key": "k2", "value": "v2"}, {"key": "k3", "value": "v3"}]}',
  },
  {
    name: "$compose (array result)",
    doc: '{"<target>": {"$compose": [{"<subtarget>": {"$path": "<path>"}}]}}',
    description: "Build an array in <target> field, by composing severals operators : $path, $at, $pointer.",
    expression: '{"result": {"$compose": [{"values": {"$path": "$.headers[0:].value"}}, {"keys": {"$path": "$.headers[0:].key"}}]}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}, {"key": "k3", "value": "v3"}], "scheme": "HTTPS"}',
    result: '{"result": [{"values": ["v1", "v2", "v3"]}, {"keys": ["k1", "k2", "k3"]}]}',
  },
  {
    name: "$compose (object result)",
    doc: '{"<target>": {"$compose": {"<subtarget>": {"$path": "<path>"}}}}',
    description: "Build an object in <target> field, by composing severals operators : $path, $at, $pointer.",
    expression: '{"result": {"$compose": {"values": {"$path": "$.headers[0:].value"}, "keys": {"$path": "$.headers[0:].key"}}}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}, {"key": "k3", "value": "v3"}]}',
    result: '{"result": {"values": ["v1", "v2", "v3"], "keys": ["k1", "k2", "k3"]}}',
  },
  {
    name: "$value",
    doc: '{"<target>": {"$value": "<value>"}}',
    description: "Put <value> in <target> field. <value> can be either a primitive type, an object or an array.",
    expression: '{"headers": {"$value": []}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}, {"key": "k3", "value": "v3"}]}',
    result: '{"headers": []}',
  },
  {
    name: "$spread",
    doc: '{"$spread": true}',
    description: "Include all properties of source object, this can be used as base for further transformation with below operators.",
    expression: '{"$spread": true}',
    data: '{"headers": [{"key": "k1", "value": "v1"}], "scheme": "HTTPS", "foo": 1}',
    result: '{"headers": [{"key": "k1", "value": "v1"}], "scheme": "HTTPS", "foo": 1}',
  },
  {
    name: '"<field>": false',
    doc: '{"$spread": true, "<field>": false}',
    description: "Exclude <field> field from resulting object",
    expression: '{"$spread": true, "foo": false}',
    data: '{"headers": [{"key": "k1", "value": "v1"}], "scheme": "HTTPS", "foo": 1}',
    result: '{"headers": [{"key": "k1", "value": "v1"}], "scheme": "HTTPS"}',
  },
  {
    name: "$remove",
    doc: '{"$spread": true, "<field>": {"$remove": true}}',
    description: 'Exclude <field> field from resulting object, like for {"<field>": false}',
    expression: '{"$spread": true, "foo": {"$remove": true}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}], "scheme": "HTTPS", "foo": 1}',
    result: '{"headers": [{"key": "k1", "value": "v1"}], "scheme": "HTTPS"}',
  },
  {
    name: "$header",
    doc: '{"<target>": {"$header": {"path": "<path>", "name": "<name>", "default": "<default_value>"}}}',
    description: "Put specified header value in <target>. <path> indicate an array of key/value headers, <name> indicate the name of the header.",
    expression: '{"hostValue": {"$header": {"path": "headers", "name": "Host"}}}',
    data: '{"headers": [{"key": "Host", "value": "otoroshi.oto.tools:9999"}, {"key": "Accept", "value": "application/json"}]}',
    result: '{"hostValue": "otoroshi.oto.tools:9999"}',
  },
  {
    name: "$includeAllKeysMatching",
    doc: '{"$spread": true, "<subname>": {"$includeAllKeysMatching": ["<expression>"]}}',
    description: "Filter an object entries based on StartsWith, Wildcard or Regex expression. A logical OR will be applied between filters.",
    expression: '{"$spread": true, "_": {"$includeAllKeysMatching": ["Wildcard(f*)", "StartsWith(fi)", "bar", "Regex(.*lo)"]}}',
    data: '{"foo": 1, "foobar": 2, "bar": 3, "baz": 4, "hello": "world", "fifou": "test"}',
    result: '{"foo": 1, "foobar": 2, "bar": 3, "hello": "world", "fifou": "test"}',
  },
  {
    name: "$excludeAllKeysMatching",
    doc: '{"$spread": true, "<subname>": {"$excludeAllKeysMatching": ["<expression>"]}}',
    description: "Filter an object entries, excluding them based on StartsWith, Wildcard or Regex expression. A logical OR will be applied between filters.",
    expression: '{"$spread": true, "_": {"$excludeAllKeysMatching": ["Wildcard(fo*)", "StartsWith(fi)", "bar", "Regex(.*lo)"]}}',
    data: '{"foo": 1, "foobar": 2, "bar": 3, "baz": 4, "hello": "world", "fifou": "test"}',
    result: '{"baz": 4}',
  },
  {
    name: "$jq",
    doc: '{"<target>": {"$jq": "<jqExpression>"}}',
    description: "Fill target field with provided JQ selector",
    expression: '{"headerKeys": {"$jq": "[.headers[].key]"}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}]}',
    result: '{"headerKeys": ["k1", "k2"]}',
  },
  {
    name: "$jqIf",
    doc: '{"<target>": {"$jqIf": {"filter": "<jqExpression>", "predicate": {"path": "<path>", "value": "<value>"}}}}',
    description: "Fill target field with provided JQ selector if and only if value located at <path> is equal to <value>",
    expression: '{"headerKeys": {"$jqIf": {"filter": "[.headers[].key]", "predicate": {"path": "target.scheme", "value": "https"}}}}',
    data: '{"headers": [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}], "target": {"scheme": "https"}}',
    result: '{"headerKeys": ["k1", "k2"]}',
  },
];

function ProjectionBlock({ item }) {
  return (
    <div className="de-proj-block">
      <h3>{item.name}</h3>
      <div className="de-proj-row">
        <div className="de-proj-left">
          <h4>Description</h4>
          <pre><code>{formatJson(item.doc)}</code></pre>
          <p>{item.description}</p>
        </div>
        <div className="de-proj-right">
          <div className="de-proj-expr-event">
            <div className="de-proj-half">
              <h4>Expression</h4>
              <pre><code>{formatJson(item.expression)}</code></pre>
            </div>
            <div className="de-proj-half">
              <h4>Event</h4>
              <pre><code>{formatJson(item.data)}</code></pre>
            </div>
          </div>
          <div className="de-proj-result">
            <h4>Result</h4>
            <pre><code>{formatJson(item.result)}</code></pre>
          </div>
        </div>
      </div>
    </div>
  );
}

export function ProjectionDocs() {
  return (
    <div className="de-proj-container">
      {projection.map((item, i) => <ProjectionBlock key={i} item={item} />)}
    </div>
  );
}
