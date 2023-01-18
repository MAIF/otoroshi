import { Host, Config, } from '@extism/as-pdk';
import { JSON } from "json-as/assembly";

function myAbort(
  message: string | null,
  fileName: string | null,
  lineNumber: u32,
  columnNumber: u32
): void { }

// @ts-ignore
@JSON
class Backend {
  id: string;
  hostname: string;
  port: u32;
  tls: boolean;
  weight: u32;
  protocol: string;
  ip_address: string;
  predicate: string;
  tls_config: string;
}

@JSON
class Apikey {
  clientId: string;
  clientName: string;
  metadata: string;
  tags: Array<string>;
}

@JSON
class User {
  name: string;
  email: string;
  profile: string;
  metadata: string;
  tags: Array<string>;
}

@JSON
class RawRequest {
  id: u64;
  method: string;
  headers: string;
  cookies: string;
  tls: boolean;
  uri: string;
  path: string;
  version: string;
  has_body: boolean;
  remote: string;
  client_cert_chain: string;
}

@JSON
class Frontend {
  domains: Array<string>;
  strict_path: string;
  exact: boolean;
  headers: string;
  query: string;
  methods: Array<string>;
}
@JSON
class RouteBackend {
  // targets: Array<Backend>;
  root: string;
  rewrite: boolean;
  load_balancing: string;
  client: string;
  health_check: string;
}

@JSON
class Route {
  id: string;
  name: string;
  description: string;
  tags: Array<string>;
  metadata: string;
  enabled: boolean;
  debug_flow: boolean;
  export_reporting: boolean;
  capture: boolean;
  groups: Array<string>;
  // frontend: Frontend | null;
  // backend: RouteBackend | null;
  backend_ref: string;
  plugins: string;
}

@JSON
class OtoroshiResponse {
  status: u32;
  headers: string;
  cookies: string;
}

@JSON
class OtoroshiRequest {
  url: string;
  method: string;
  headers: string;
  version: string;
  client_cert_chain: string;
  backend: Backend | null;
  cookies: string;
}

@JSON
class WasmQueryContext {
  snowflake: string;
  backend: Backend | null;
  apikey: Apikey | null;
  user: User | null;
  raw_request: RawRequest | null;
  config: string;
  global_config: string;
  attrs: string;
  route: Route | null;
  // raw_request_body: string;
  // request: OtoroshiRequest | null;
}

@JSON
class WasmAccessValidatorContext {
  snowflake: string;
  apikey: Apikey;
  user: User;
  request: RawRequest;
  config: string;
  global_config: string;
  attrs: string;
  route: Route;
}

@JSON
class WasmRequestTransformerContext {
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
class WasmResponseTransformerContext {
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
class WasmSinkContext {
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
class WasmQueryResponse {
  headers: string;
  body: string;
  status: u32;
}

@JSON
class WasmAccessValidatorError {
  message: string;
  status: u32;
}

@JSON
class WasmAccessValidatorResponse {
  result: boolean;
  error: WasmAccessValidatorError | null;
}

@JSON
class WasmTransformerResponse {
  headers: string;
  cookies: string;
  body: string;
}

@JSON
class WasmSinkMatchesResponse {
  result: boolean;
}

@JSON
class WasmSinkHandleResponse {
  status: u32;
  headers: string;
  body: string;
  bodyase64: string;
}



export function count_vowels(): i32 {
  let str = Host.inputString();

  // let context = <JSON.Obj>(JSON.parse(str))

  let context = JSON.parse<WasmQueryContext>(str)
  // let host = context.headers.Host
  // let foo = Config.get('foo')


  // Host.outputString(`{ "foo_from_header": ${context.headers.foo}, "Host": ${host}, "foo_config": ${(foo == null ? "null" : foo)} }`);

  Host.outputString(`{ "body": ${JSON.stringify<WasmQueryContext>(context)}}`)
  // Host.outputString(`{ "body": ${context.stringify()}`)
  return 0;
}
