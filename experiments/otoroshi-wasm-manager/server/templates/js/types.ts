export interface Backend {
  id: string;
  hostname: string;
  port: number;
  tls: boolean;
  weight: number;
  protocol: string;
  ip_address?: string;
  predicate: any;
  tls_config: any;
}

export interface Apikey {
  clientId: string;
  clientName: string;
  metadata: Map<string, string>;
  tags: [String];
}

export interface User {
  name: string;
  email: string;
  profile: any;
  metadata: Map<string, string>;
  tags: [string];
}

export interface RawRequest {
  id: number;
  method: string;
  headers: Map<string, string>;
  cookies: any;
  tls: boolean;
  uri: string;
  path: string;
  version: string;
  has_body: boolean;
  remote: string;
  client_cert_chain: any;
}

export interface Frontend {
  domains: [string];
  strict_path?: string;
  exact: boolean;
  headers: Map<string, string>;
  query: Map<string, string>;
  methods: [string];
}

export interface HealthCheck {
  enabled: boolean;
  url: string;
}

export interface RouteBackend {
  targets: [Backend];
  root: string;
  rewrite: boolean;
  load_balancing: any;
  client: any;
  health_check?: HealthCheck;
}

export interface Route {
  id: string;
  name: string;
  description: string;
  tags: [string];
  metadata: Map<string, string>;
  enabled: boolean;
  debug_flow: boolean;
  export_reporting: boolean;
  capture: boolean;
  groups: [string];
  frontend: Frontend,
  backend: RouteBackend,
  backend_ref?: string;
  plugins: any;
}

export interface OtoroshiResponse {
  status: number;
  headers: Map<string, string>;
  cookies: any;
}

export interface OtoroshiRequest {
  url: string;
  method: string;
  headers: Map<string, string>;
  version: string;
  client_cert_chain: any;
  backend?: [Backend];
  cookies: any;
}


export interface WasmQueryContext {
  snowflake?: string;
  backend: Backend;
  apikey?: [Apikey];
  user?: [User];
  raw_request: RawRequest,
  config: any;
  global_config: any;
  attrs: any;
  route: Route,
  raw_request_body?: string;
  request: OtoroshiRequest,
}


export interface WasmAccessValidatorContext {
  snowflake?: string;
  apikey?: [Apikey];
  user?: [User];
  request: RawRequest,
  config: any;
  global_config: any;
  attrs: any;
  route: Route,
}


export interface WasmRequestTransformerContext {
  snowflake?: string;
  raw_request: OtoroshiRequest,
  otoroshi_request: OtoroshiRequest,
  backend: Backend,
  apikey?: [Apikey];
  user?: [User];
  request: RawRequest,
  config: any;
  global_config: any;
  attrs: any;
  route: Route,
}


export interface WasmResponseTransformerContext {
  snowflake?: string;
  raw_response: OtoroshiResponse,
  otoroshi_response: OtoroshiResponse,
  apikey?: [Apikey];
  user?: [User];
  request: RawRequest,
  config: any;
  global_config: any;
  attrs: any;
  route: Route,
  body?: string;
}


export interface WasmSinkContext {
  snowflake?: string;
  request: RawRequest,
  config: any;
  global_config: any;
  attrs: any;
  origin: string;
  status: number;
  message: string;
}

export interface OtoroshiPluginResponse {
  content: string;
}

export interface WasmQueryResponse {
  headers?: Map<string, string>;
  body: string;
  status: number;
}

export interface WasmAccessValidatorError {
  message: string;
  status: number;
}

export interface WasmAccessValidatorResponse {
  result: boolean;
  error?: WasmAccessValidatorError;
}

export interface WasmTransformerResponse {
  headers: Map<string, string>;
  cookies: any;
  body?: string;
}

export interface WasmSinkMatchesResponse {
  result: boolean;
}

export interface WasmSinkHandleResponse {
  status: number;
  headers: Map<string, string>;
  body?: string;
  bodyBase64?: string;
}