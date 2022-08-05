const MISSING_TYPES = [
  'Unknown',
  'Empty',
  'Object',
  'Array',
  'Null',
  'Done',
  'LiveStats',
  'TokenResponse',
  'HostMetrics',
  'CertValidResponse'
]

const CONVERTED_TYPES = {
  "NgDomainAndPath": "String",
  "PluginDescriptionsResponse": "[Json]",
  "TenantId": "String",
  "TeamId": "String",
  "Exporter": "Json",
  "TargetPredicate": "Json",
  "AlgoSettings": "Json",
  "EntityIdentifier": "String",
  "VerifierStrategy": "Json",
  "JwtVerifier": "Json",
  "GeolocationSettings": "Json",
  "AuthModuleConfig": "Json",
  "JwtTokenLocation": "Json",
  "DataExporterConfigType": "Json",
  "PluginType": "Json",
  "TlsMode": "Json",
  "OtoroshiAdminType": "Json",
  "OutageStrategy": "Json",
  "ClientAuth": "Json"
}

const RENAMED_TYPES = {
  location: '_loc'
}

const AVAILABLE_TYPES = [
  "get"// , "post", "put", "patch", "head"
]

const AVAILABLE_OPERATIONS = [
  { verb: "get", operation: "read" },
  { verb: "post", operation: "create" },
  { verb: "put", operation: "update" },
  { verb: "patch", operation: "patch" },
  { verb: "delete", operation: "delete" }
]

const REFACTO_TYPES = {
  proxy: 'Json'
}

module.exports = {
  MISSING_TYPES,
  AVAILABLE_OPERATIONS,
  AVAILABLE_TYPES,
  CONVERTED_TYPES,
  RENAMED_TYPES,
  REFACTO_TYPES
}