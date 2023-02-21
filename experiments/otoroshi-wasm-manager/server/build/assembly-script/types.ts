
// @ts-ignore
@JSON
export class Backend {
  id: string;
  hostname: string;
  port: u32;
  tls: boolean;
  weight: u32;
  protocol: string;
  ip_address: string | null;
  predicate: string;
  tls_config: string;
}

@JSON
export class Apikey {
  clientId: string;
  clientName: string;
  metadata: Map<string, string>;
  tags: Array<string>;
}

@JSON
export class User {
  name: string;
  email: string;
  profile: string;
  metadata: Map<string, string>;
  tags: Array<string>;
}

@JSON
export class RawRequest {
  id: u64;
  method: string;
  headers: Map<string, string>;
  cookies: Map<string, string>;
  tls: boolean;
  uri: string;
  path: string;
  version: string;
  has_body: boolean;
  remote: string;
  client_cert_chain: string;
}

@JSON
export class Frontend {
  domains: Array<string>;
  strict_path: string | null;
  exact: boolean;
  headers: Map<string, string>;
  query: Map<string, string>;
  methods: Array<string>;
}
@JSON
export class RouteBackend {
  targets: Array<Backend>;
  root: string;
  rewrite: boolean;
  load_balancing: string;
  client: string;
}

@JSON
export class Route {
  id: string;
  name: string;
  description: string;
  tags: Array<string>;
  metadata: Map<string, string>;
  enabled: boolean;
  debug_flow: boolean;
  export_reporting: boolean;
  capture: boolean;
  groups: Array<string>;
  frontend: Frontend;
  backend: RouteBackend;
  backend_ref: string | null;
  plugins: string | null;
}

@JSON
export class OtoroshiResponse {
  status: u32;
  headers: Map<string, string>;
  cookies: Map<string, string>;
}

@JSON
export class OtoroshiRequest {
  url: string;
  method: string;
  headers: Map<string, string>;
  version: string;
  client_cert_chain: string;
  backend: Backend | null;
  cookies: Map<string, string>;
}

@JSON
export class WasmQueryContext {
  snowflake: string;
  backend: Backend | null;
  apikey: Apikey | null;
  user: User | null;
  raw_request: RawRequest | null;
  config: string;
  global_config: string;
  attrs: string;
  route: Route | null;
  raw_request_body: string | null;
  request: OtoroshiRequest | null;
}

@JSON
export class WasmAccessValidatorContext {
  snowflake: string;
  apikey: Apikey | null;
  user: User | null;
  request: RawRequest;
  config: string;
  global_config: string;
  attrs: string;
  route: Route;
}

@JSON
export class WasmRequestTransformerContext {
  snowflake: string;
  raw_request: OtoroshiRequest | null;
  otoroshi_request: OtoroshiRequest | null;
  backend: Backend | null;
  apikey: Apikey | null;
  user: User | null;
  request: RawRequest | null;
  config: string;
  global_config: string;
  attrs: string;
  route: Route | null;
}

@JSON
export class WasmResponseTransformerContext {
  snowflake: string;
  raw_response: OtoroshiResponse | null;
  otoroshi_response: OtoroshiResponse | null;
  apikey: Apikey | null;
  user: User | null;
  request: RawRequest | null;
  config: string;
  global_config: string;
  attrs: string;
  route: Route | null;
  body: string;
}


@JSON
export class WasmSinkContext {
  snowflake: string;
  request: RawRequest | null;
  config: string;
  global_config: string;
  attrs: string;
  origin: string;
  status: u32;
  message: string;
}


@JSON
export class WasmQueryResponse {
  headers: Map<string, string>;
  body: string;
  status: u32;
}

@JSON
export class WasmAccessValidatorError {
  message: string;
  status: u32;
}

@JSON
export class WasmAccessValidatorResponse {
  result: boolean;
  error: WasmAccessValidatorError | null;
}

@JSON
export class WasmTransformerResponse {
  headers: Map<string, string>;
  cookies: Map<string, string>;
  body: string;
}

@JSON
export class WasmSinkMatchesResponse {
  result: boolean;
}

@JSON
export class WasmSinkHandleResponse {
  status: u32;
  headers: Map<string, string>;
  body: string;
  bodybase64: string;
}

