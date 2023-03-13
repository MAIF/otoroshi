import AdditionalHeadersIn from './AdditionalHeadersIn';
import AdditionalHeadersOut from './AdditionalHeadersOut';
import AllowHttpMethods from './AllowHttpMethods';
import ApikeyCalls from './ApikeyCalls';
import ApikeyQuotas from './ApikeyQuotas';
import AuthModule from './AuthModule';
import BuildMode from './BuildMode';
import CanaryMode from './CanaryMode';
import ContextValidation from './ContextValidation';
import Cors from './Cors';
import DisableHttp10 from './DisableHttp10';
import EndlessHttpResponse from './EndlessHttpResponse';
import EurekaServerSink from './EurekaServerSink';
import EurekaTarget from './EurekaTarget';
import ExternalEurekaTarget from './ExternalEurekaTarget';
import ForceHttpsTraffic from './ForceHttpsTraffic';
import GlobalMaintenanceMode from './GlobalMaintenanceMode';
import GlobalPerIpAddressThrottling from './GlobalPerIpAddressThrottling';
import GlobalThrottling from './GlobalThrottling';
import GraphQLBackend from './GraphQLBackend';
import GraphQLProxy from './GraphQLProxy';
import GraphQLQuery from './GraphQLQuery';
import GzipResponseCompressor from './GzipResponseCompressor';
import HeadersValidation from './HeadersValidation';
import HtmlPatcher from './HtmlPatcher';
import Http3Switch from './Http3Switch';
import IpAddressAllowedList from './IpAddressAllowedList';
import IpAddressBlockList from './IpAddressBlockList';
import JQ from './JQ';
import JQRequest from './JQRequest';
import JQResponse from './JQResponse';
import JsonToXmlRequest from './JsonToXmlRequest';
import JsonToXmlResponse from './JsonToXmlResponse';
import JwtSigner from './JwtSigner';
import JwtVerification from './JwtVerification';
import JwtVerificationOnly from './JwtVerificationOnly';
import MaintenanceMode from './MaintenanceMode';
import MissingHeadersIn from './MissingHeadersIn';
import MissingHeadersOut from './MissingHeadersOut';
import MockResponses from './MockResponses';
import NgAuthModuleExpectedUser from './NgAuthModuleExpectedUser';
import NgAuthModuleUserExtractor from './NgAuthModuleUserExtractor';
import NgBackend from './NgBackend';
import NgDefaultRequestBody from './NgDefaultRequestBody';
import NgErrorRewriter from './NgErrorRewriter';
import NgFrontend from './NgFrontend';
import NgHttpClientCache from './NgHttpClientCache';
import NgLegacyApikeyCall from './NgLegacyApikeyCall';
import NgLegacyAuthModuleCall from './NgLegacyAuthModuleCall';
import OtoroshiChallenge from './OtoroshiChallenge';
import OtoroshiHeadersIn from './OtoroshiHeadersIn';
import OtoroshiInfos from './OtoroshiInfos';
import OverrideHost from './OverrideHost';
import PublicPrivatePaths from './PublicPrivatePaths';
import QueryTransformer from './QueryTransformer';
import RBAC from './RBAC';
import ReadOnlyCalls from './ReadOnlyCalls';
import Redirection from './Redirection';
import RemoveHeadersIn from './RemoveHeadersIn';
import RemoveHeadersOut from './RemoveHeadersOut';
import Robots from './Robots';
import RoutingRestrictions from './RoutingRestrictions';
import S3Backend from './S3Backend';
import SOAPAction from './SOAPAction';
import SendOtoroshiHeadersBack from './SendOtoroshiHeadersBack';
import SnowMonkeyChaos from './SnowMonkeyChaos';
import StaticBackend from './StaticBackend';
import StaticResponse from './StaticResponse';
import TailscaleSelectTargetByName from './TailscaleSelectTargetByName';
import TcpTunnel from './TcpTunnel';
import TunnelPlugin from './TunnelPlugin';
import UdpTunnel from './UdpTunnel';
import W3CTracing from './W3CTracing';
import XForwardedHeaders from './XForwardedHeaders';
import XmlToJsonRequest from './XmlToJsonRequest';
import XmlToJsonResponse from './XmlToJsonResponse';
import WasmBackend from './WasmBackend';
import WasmAccessValidator from './WasmAccessValidator';
import WasmRequestTransformer from './WasmRequestTransformer';
import WasmResponseTransformer from './WasmResponseTransformer';
import WasmRequestHandler from './WasmRequestHandler';
import WasmRouteMatcher  from './WasmRouteMatcher';
import WasmSink  from './WasmSink';
import WasmJob  from './WasmJob';

export const Backend = NgBackend;
export const Frontend = NgFrontend;

export const Plugins = [
  AdditionalHeadersIn,
  AdditionalHeadersOut,
  AllowHttpMethods,
  ApikeyCalls,
  ApikeyQuotas,
  AuthModule,
  BuildMode,
  CanaryMode,
  ContextValidation,
  Cors,
  DisableHttp10,
  EndlessHttpResponse,
  EurekaServerSink,
  EurekaTarget,
  ExternalEurekaTarget,
  ForceHttpsTraffic,
  GlobalMaintenanceMode,
  GlobalPerIpAddressThrottling,
  GlobalThrottling,
  GraphQLBackend,
  GraphQLProxy,
  GraphQLQuery,
  GzipResponseCompressor,
  HeadersValidation,
  HtmlPatcher,
  Http3Switch,
  IpAddressAllowedList,
  IpAddressBlockList,
  JQ,
  JQRequest,
  JQResponse,
  JsonToXmlRequest,
  JsonToXmlResponse,
  JwtSigner,
  JwtVerification,
  JwtVerificationOnly,
  MaintenanceMode,
  MissingHeadersIn,
  MissingHeadersOut,
  MockResponses,
  NgAuthModuleExpectedUser,
  NgAuthModuleUserExtractor,
  NgDefaultRequestBody,
  NgErrorRewriter,
  NgHttpClientCache,
  NgLegacyApikeyCall,
  NgLegacyAuthModuleCall,
  OtoroshiChallenge,
  OtoroshiHeadersIn,
  OtoroshiInfos,
  OverrideHost,
  PublicPrivatePaths,
  QueryTransformer,
  RBAC,
  ReadOnlyCalls,
  Redirection,
  RemoveHeadersIn,
  RemoveHeadersOut,
  Robots,
  RoutingRestrictions,
  S3Backend,
  SOAPAction,
  SendOtoroshiHeadersBack,
  SnowMonkeyChaos,
  StaticBackend,
  StaticResponse,
  TailscaleSelectTargetByName,
  TcpTunnel,
  TunnelPlugin,
  UdpTunnel,
  W3CTracing,
  XForwardedHeaders,
  XmlToJsonRequest,
  XmlToJsonResponse,
  WasmBackend,
  WasmAccessValidator,
  WasmRequestTransformer,
  WasmResponseTransformer, 
  WasmRequestHandler,
  WasmRouteMatcher,
  WasmSink,
  WasmJob,
];
