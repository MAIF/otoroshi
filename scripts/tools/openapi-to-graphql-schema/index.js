const fs = require('fs');
const openapi = require('../../../otoroshi/public/openapi.json');
const { capitalize, values, entries, joinLines, getUniqueListBy, openApiPathToGraphQLType, isPrimitiveTypes } = require('./src/utils')
const { AVAILABLE_TYPES, AVAILABLE_OPERATIONS, CONVERTED_TYPES, REFACTO_TYPES, RENAMED_TYPES } = require('./src/config')
let MISSING_TYPES = require('./src/config').MISSING_TYPES

const BASIC_ADMIN_API_KEY_CREDENTIALS = process.env.BASIC_ADMIN_API_KEY_CREDENTIALS || "YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA=="
const OTOROSHI_API = process.env.OTOROSHI_API || "http://otoroshi-api.oto.tools:9999"

const GraphQLFormatter = {
  name: n => RENAMED_TYPES[n] || n,
  fieldDescription: description => description !== '???' ? `# ${description}` : '',
  returnType: ({ returnType, name, format }) => {
    if (REFACTO_TYPES[name])
      return REFACTO_TYPES[name]

    if (!returnType || returnType.includes(" "))
      return "Json" // TODO

    if (["Integer", "Number"].includes(returnType)) {
      if ("int64" === "format" || "int32" === "format")
        return "Int"
      else if ("double")
        return "Float"
      else
        return "Long"
    }

    if (MISSING_TYPES.includes(returnType))
      return 'Json'

    if (CONVERTED_TYPES[returnType])
      return CONVERTED_TYPES[returnType]

    return returnType
  },
  enum: (name, description, elements) => `
  """
  ${description}
  """
  enum ${name} { 
   ${elements.join("\n ")}
  }`,
  stringType: (name, description) => `
  """
  ${description}
  """
  type ${name} {
    todo: String
  }`,
  field: ({ name, returnType, description, capitalized = true, isArray = false, format }) => {
    const formattedType = GraphQLFormatter.returnType({
      returnType: capitalized ? capitalize(returnType) : returnType,
      name,
      format
    })
    const finalReturnType = isArray ? `[${formattedType}]` : formattedType;
    return `
 ${name}: ${finalReturnType} ${GraphQLFormatter.fieldDescription(description)}`
  },
  query: fields => `
type Query {
${fields}
}`
}

const GraphQLConverter = {
  enum: (name, description, content) => ({
    name,
    type: GraphQLFormatter.enum(name, description, content),
  }),
  string: (name, description) => ({
    name,
    type: GraphQLFormatter.stringType(name, description)
  }),
  null: { name: "", type: null },
  object: (properties) => {
    return entries(properties || {})
      .map(([rawName, fields]) => {
        const name = GraphQLFormatter.name(rawName.replace(/-/g, "_"))
        if (fields.oneOf) {
          if (fields.oneOf.length === 2 &&
            fields.oneOf.every(f => f["type"]) &&
            ((fields.oneOf[0].type === 'string' && fields.oneOf[1].type === 'object') ||
              (fields.oneOf[0].type === 'object' && fields.oneOf[1].type === 'string'))
          ) {
            return GraphQLFormatter.field({
              name,
              returnType: 'Json',
              description: fields.description,
              capitalize: false
            })
          }
          else if (fields.oneOf.length === 2 &&
            fields.oneOf.find(f => f["$ref"]) &&
            fields.oneOf.find(f => f["type"])) {
            return GraphQLFormatter.field({
              name,
              returnType: fields.oneOf.find(f => f["$ref"])["$ref"].split(".").slice(-1)[0],
              description: fields.description,
              capitalize: false
            })
          }
          else {
            return `
 ${name}: ${[...new Set(
              fields.oneOf
                .map(r => r["$ref"] || capitalize(r["type"]))
                .map(r => {
                  if (r.includes("."))
                    return r.split(".").slice(-1)[0]
                  else {
                    const type = r.split("/").slice(-1)[0]
                    if (type === 'Null')
                      return "Object"
                    return type
                  }
                })
                .filter(f => !["Object", "Array"].includes(f))
                .map(r => GraphQLFormatter.returnType({ returnType: r }))
                .filter(r => r !== name))][0]
              }`
          }
        }
        else if (fields["$ref"]) {
          return GraphQLFormatter.field({ name, returnType: fields["$ref"].split(".").slice(-1)[0], description: fields.description, capitalized: false })
        }
        else if (fields.type === "array") {
          let ref = fields.items['$ref'] || fields.items['type']
          if (ref)
            ref = ref.includes(".") ? ref.split(".").slice(-1)[0] : ref.split("/").slice(-1)[0];
          return GraphQLFormatter.field({
            name,
            format: fields.items['format'],
            returnType: ref,
            description: fields.description,
            capitalized: true,
            isArray: true
          })
        }
        else
          return GraphQLFormatter.field({ name, returnType: fields.type, description: fields.description, format: fields.format })
      })
      .join("")
  },
  oneOf: (name, description, items) => ({
    name,
    fields: [],
    type: `
"""
${description !== "???" ? description : ''}
"""
union ${name} = ${[...new Set(
      items
        .map(r => r["$ref"] || capitalize(r["type"]))
        .map(r => {
          if (r.includes("."))
            return r.split(".").slice(-1)[0]
          else {
            const type = r.split("/").slice(-1)[0]
            if (type === 'Null')
              return "Object"
            return type
          }
        })
        .filter(f => !["Object", "Array"].includes(f))
        .map(r => GraphQLFormatter.returnType({ returnType: r }))
        .filter(r => r !== name))]
        .join(" | ")
      }`
  }),
  component: ({ type, description, properties, ...props }) => {
    const name = GraphQLFormatter.name(props.name.replace(/-/g, "_"))

    if (props.enum)
      return GraphQLConverter.enum(name, description, props.enum)
    else if (type === 'string')
      return GraphQLConverter.string(name, description)

    let body = ""
    let fields = []
    // TODO - manage types which are compose of other type
    // ex : ScriptsList
    if (type === "array") {
      if (props.items && props.items.properties) {
        body += entries(props.items.properties)
          .map(([rawName, fields]) => {
            const name = GraphQLFormatter.name(rawName.replace(/-/g, "_"))
            return GraphQLFormatter.field({ name, returnType: fields.type, description: fields.description, format: fields.format })
          })
        fields = entries(props.items.properties)
          .map(([rawName, fields]) => ({
            name: rawName,
            type: GraphQLFormatter.returnType({ returnType: capitalized ? capitalize(fields.type) : fields.types, format: fields.format })
          }))
      } else {
        return GraphQLConverter.null
      }
    }
    else if (type === 'object') {
      if (!properties || Object.keys(properties || {}).length === 0)
        return
      else {
        body += GraphQLConverter.object(properties)
        /***********************/
        fields = entries(properties)
          .map(([rawName, fields]) => {
            const name = GraphQLFormatter.name(rawName.replace(/-/g, "_"))
            let type
            if (fields.oneOf) {
              if (fields.oneOf.length === 2 && fields.oneOf.every(f => f["type"]) &&
                ((fields.oneOf[0].type === 'string' && fields.oneOf[1].type === 'object') ||
                  (fields.oneOf[0].type === 'object' && fields.oneOf[1].type === 'string'))
              ) {
                type = GraphQLFormatter.returnType({ returnType: 'Json' })
              }
              else if (fields.oneOf.length === 2 &&
                fields.oneOf.find(f => f["$ref"]) &&
                fields.oneOf.find(f => f["type"]))
                type = GraphQLFormatter.returnType({
                  returnType: capitalize(
                    fields.oneOf.find(f => f["$ref"])["$ref"].split(".").slice(-1)[0]),
                })
              else {
                type = [...new Set(
                  fields.oneOf
                    .map(r => r["$ref"] || capitalize(r["type"]))
                    .map(r => {
                      if (r.includes("."))
                        return r.split(".").slice(-1)[0]
                      else {
                        const type = r.split("/").slice(-1)[0]
                        if (type === 'Null')
                          return "Object"
                        return type
                      }
                    })
                    .filter(f => !["Object", "Array"].includes(f))
                    .map(r => GraphQLFormatter.returnType({ returnType: r }))
                    .filter(r => r !== name))][0]
              }
            }
            else if (fields["$ref"]) {
              type = GraphQLFormatter.returnType({ returnType: fields["$ref"].split(".").slice(-1)[0] })
            }
            else if (fields.type === "array") {
              let ref = fields.items['$ref']
              if (ref)
                ref = ref.includes(".") ? ref.split(".").slice(-1)[0] : ref.split("/").slice(-1)[0];
              type = GraphQLFormatter.returnType({ returnType: capitalize(ref) })
            }
            else
              type = GraphQLFormatter.returnType({ returnType: capitalize(fields.type), format: fields.format })
            return {
              name: rawName,
              type
            }
          })
        /***********************/
      }
    } else if (props.oneOf) {
      if (name === 'Any')
        return null

      return GraphQLConverter.oneOf(name, description, props.oneOf)
    }

    if (body.length === 0)
      return null;

    return {
      name,
      fields,
      type: `
  ${description !== '???' ? `
"""
${description}
"""` : ''}
type ${name} {${body}
}`
    }
  },
  extractResponsesTypes: path => [...new Set(AVAILABLE_TYPES
    .filter(verb => path[verb])
    .flatMap(verb => GraphQLConverter.extractResponseTypes(path, verb)))],
  extractResponseTypes: (path, verb) => {
    return values(path[verb].responses)
      .flatMap(resp => values(resp.content)
        .flatMap(content => {
          const ref = content?.schema["$ref"] || (content?.schema?.items ? content.schema.items["$ref"] : undefined);

          if (!ref)
            return null;

          const type = ref.includes(".") ? ref.split(".").slice(-1)[0] : ref.split("/").slice(-1)[0];

          return {
            type,
            isArray: !!content?.schema?.items
          }
        })
      )
  },
  pathToGraphQLQuery: ({ path, ...endpoints }) => {
    const operations = AVAILABLE_OPERATIONS
      .filter(ope => endpoints[ope.verb]);

    const components = GraphQLConverter.extractResponsesTypes(endpoints)
      .filter(f => f && f.type)
      .map(f => f.type)

    let queryName = openApiPathToGraphQLType(path)

    const returnType = GraphQLFormatter.returnType({
      returnType: components.length === 0 ? components[0] : components.find(f => f !== "ErrorResponse")
    })

    // services: [ServiceDescriptor] 
    // servicesId(id : String): ServiceDescriptor
    // servicesTemplate: ServiceDescriptor
    if (returnType.includes("ServiceDescriptor") && queryName.startsWith('services'))
      queryName = `old_${queryName}`

    if (returnType?.endsWith("List") || returnType === "Any")
      return null

    return operations.map(({ verb, operation }) => {
      const responses = GraphQLConverter.extractResponseTypes(endpoints, verb)

      const { isArray } = responses
        .filter(r => r && r.type)
        .find(r => r.type !== 'ErrorResponse') || { isArray: false }

      const url = `${OTOROSHI_API}${path.replace('{', '${params.')}`;
      const method = verb.toUpperCase();
      const headers = `{\\\"Accept\\\": \\\"application/json\\\", \\\"Authorization\\\": \\\"Basic ${BASIC_ADMIN_API_KEY_CREDENTIALS}\\\"}`

      const parameters = (endpoints[verb].parameters || [])
        .map(parameter => `${parameter.name} : ${GraphQLFormatter.returnType({
          returnType: capitalize(parameter.schema.type)
        })}`)

      const parametersStr = parameters.length > 0 ? `(${parameters.join(",")})` : ''

      return {
        verb,
        queryName,
        type: `  ${operation !== "read" ? operation + capitalize(queryName) : queryName}${parametersStr}: ${isArray ? `[${GraphQLFormatter.returnType({ returnType })}]` : GraphQLFormatter.returnType({ returnType })} @rest(url: "${url}", method: "${method}", headers: "${headers}")`,
        data: {
          name: operation !== "read" ? operation + capitalize(queryName) : queryName,
          returnType: GraphQLFormatter.returnType({ returnType }),
          parameters: endpoints[verb].parameters || []
        }
      }
    })
  }
}

const GraphQLParser = {
  saveFile: (types, queries) => {
    const uniqueTypes = getUniqueListBy(types, "name")
      .map(f => f.type)

    const strQueries = GraphQLFormatter.query(joinLines(queries
      .map(f => f.type)))

    return new Promise((resolve, reject) => {
      fs.writeFile('./dist/admin-api-graphql.graphql',
        joinLines([
          ...uniqueTypes,
          strQueries
        ]),
        (err, data) => {
          if (err) {
            console.log(err);
            return reject()
          }
          console.log(`[${uniqueTypes.length}] Types has been updated!`);
          console.log(`[${queries.length}] Queries has been updated!`);
          return resolve()
        });
    })
  },
  run: async () => {
    const components = Object.entries(openapi.components.schemas)
      .map(([name, component]) => ({
        name: name.split(".").slice(-1)[0],
        ...component
      }));

    const paths = Object.entries(openapi.paths)
      .map(([path, endpoints]) => ({
        path,
        ...endpoints
      }))

    components.forEach(component => {
      if (component.type === 'object' && (!component.properties || Object.keys(component.properties || {}).length === 0)) {
        MISSING_TYPES = [...MISSING_TYPES, component.name]
      }
    })

    const queriesAndMutations = paths
      .filter(p => !p.path.includes("bulk") &&
        p.path.startsWith('/api') &&
        !p.path.endsWith('form') &&
        !p.path.includes('forms') &&
        !p.path.includes("live/host")
      )
      .flatMap(path => GraphQLConverter.pathToGraphQLQuery(path))
      .filter(f => f && f.type)

    const rawQueries = queriesAndMutations
      .filter(f => f.verb === "get")
      .sort((a, b) => a.queryName.localeCompare(b.queryName))

    const rawTypes =
      components
        .filter(component => !CONVERTED_TYPES[component.name])
        .map(component => GraphQLConverter.component(component))
        .filter(f => f && f.type)

    const graphTypes =
      [...rawTypes
        .flatMap(curr => [curr.name, ...(curr.fields || []).map(r => r.type)]),
      ...rawQueries.map(q => q.data.returnType)
      ].reduce((acc, curr) => {
        if (acc[curr])
          acc[curr] = acc[curr] + 1
        else
          acc[curr] = 1
        return acc
      }, {})

    const usedTypes = Object.entries(graphTypes)
      .filter(g => g[1] > 1)
      .map(r => r[0])

    const types = rawTypes.filter(t => usedTypes.includes(t.name))

    return GraphQLParser.saveFile([...types], rawQueries)
      .then(() => ({ queries: rawQueries, types: rawTypes }))
  }
}

const QueryGenerator = {
  recursiveQuery: (returnType, graphTypes, indent) => {
    const fields = [...(graphTypes[returnType] || [])]

    return fields
      .sort((a, b) => isPrimitiveTypes(a.type) && isPrimitiveTypes(b.type) ? a.name.localeCompare(b.name) : isPrimitiveTypes(a.type) ? -1 : 1)
      .map(({ name, type }) => {
        const spaces = [...new Array(indent)].join('\t')
        const formattedName = GraphQLFormatter.name(name)

        if (isPrimitiveTypes(type))
          return `${spaces}${formattedName}`
        else {
          return `${spaces}${formattedName} {
  ${QueryGenerator.recursiveQuery(type, graphTypes, indent + 1)}
  ${spaces}}`
        }
      }).join("\n  ")
  },
  run: ({ queries, types }) => {
    const graphTypes = types
      .reduce((acc, curr) => ({ ...acc, [curr.name]: curr.fields }), {})

    const adminOtoroshiQueries = queries
      .map(q => q.data)
      .map(({ name, returnType, parameters }) => {
        const formattedName = GraphQLFormatter.name(name)
        const queryParams = parameters.length > 0 ?
          `(${parameters.map(p => `$${GraphQLFormatter.name(p.name)}: ${capitalize(GraphQLFormatter.returnType({ returnType: p.schema.type }))}`).join(",")})` : ''
        const subQueryParams = parameters.length > 0 ?
          `(${parameters.map(p => `${GraphQLFormatter.name(p.name)}: $${GraphQLFormatter.name(p.name)}`).join(",")})` : ''

        const hasFields = !isPrimitiveTypes(returnType)

        if (hasFields)
          return `
query ${formattedName}${queryParams} {
  ${formattedName}${subQueryParams} {
  ${QueryGenerator.recursiveQuery(returnType, graphTypes, 2)}
  }
}`
        else
          return `
query ${formattedName}${queryParams} {
  ${formattedName}${subQueryParams}
}`
      })

    QueryGenerator.save(adminOtoroshiQueries)
  },
  save: data => {
    fs.writeFile('./dist/queries.graphql', joinLines(data),
      (err, data) => {
        if (err) {
          console.log(err);
        }
        console.log("Queries file has been updated!");
      })
  }
}

GraphQLParser
  .run()
  .then(QueryGenerator.run)