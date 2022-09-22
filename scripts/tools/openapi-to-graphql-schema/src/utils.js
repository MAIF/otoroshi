const capitalize = str => (str || "").charAt(0).toUpperCase() + (str || "").slice(1)
const lowercase = str => (str || "").charAt(0).toLowerCase() + (str || "").slice(1)
const values = obj => Object.values(obj || {})
const entries = obj => Object.entries(obj || {})
const toCamel = (s) => s.replace(/([-_][a-z])/ig, ($1) => $1.toUpperCase().replace('-', '').replace('_', ''));
const getUniqueListBy = (arr, key) => [...new Map(arr.map(item => [item[key], item])).values()]
const joinLines = arr => arr.join('\n')
const isPrimitiveTypes = type => [
  "String",
  "Boolean",
  "Json",
  "Long",
  "Int",
  "Float"
].includes(type
  .replace('[', '')
  .replace(']', ''))
const openApiPathToGraphQLType = path => lowercase(toCamel(path
  .replace("/api/experimental/", "")
  .replace("/api/", "")
  .replace(/{/g, "")
  .replace(/}/g, "")
  .replace(/\./g, "_")
  .replace(/\/_/g, "_")
  .replace(/\//g, "_")))

module.exports = {
  capitalize,
  lowercase,
  values,
  entries,
  toCamel,
  getUniqueListBy,
  joinLines,
  isPrimitiveTypes,
  openApiPathToGraphQLType
}