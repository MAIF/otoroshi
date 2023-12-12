import AdditionalHeadersIn from './AdditionalHeadersIn';
import AdditionalHeadersOut from './AdditionalHeadersOut';
import AllowHttpMethods from './AllowHttpMethods';
import ApikeyCalls from './ApikeyCalls';
import ApikeyQuotas from './ApikeyQuotas';
import ApikeyAuthModule from './ApikeyAuthModule';
import AuthModule from './AuthModule';
import BuildMode from './BuildMode';
import BasicAuthCaller from './BasicAuthCaller';
import BrotliResponseCompressor from './BrotliResponseCompressor';
import CanaryMode from './CanaryMode';
import ContextValidation from './ContextValidation';
import NgCorazaWAF from './NgCorazaWAF';
import Cors from './Cors';
import DisableHttp10 from './DisableHttp10';
import EndlessHttpResponse from './EndlessHttpResponse';
import EurekaServerSink from './EurekaServerSink';
import EurekaTarget from './EurekaTarget';
import ExternalEurekaTarget from './ExternalEurekaTarget';
import ForceHttpsTraffic from './ForceHttpsTraffic';
import ForwardedHeader from './ForwardedHeader';
import GlobalMaintenanceMode from './GlobalMaintenanceMode';
import GlobalPerIpAddressThrottling from './GlobalPerIpAddressThrottling';
import GlobalThrottling from './GlobalThrottling';
import GraphQLBackend from './GraphQLBackend';
import GraphQLProxy from './GraphQLProxy';
import GraphQLQuery from './GraphQLQuery';
import GzipResponseCompressor from './GzipResponseCompressor';
import HMACCaller from './HMACCaller';
import HMACValidator from './HMACValidator';
import HeadersValidation from './HeadersValidation';
import HtmlPatcher from './HtmlPatcher';
import Http3Switch from './Http3Switch';
import ImageReplacer from './ImageReplacer';
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
import MultiAuthModule from './MultiAuthModule';
import NgAuthModuleExpectedUser from './NgAuthModuleExpectedUser';
import NgAuthModuleUserExtractor from './NgAuthModuleUserExtractor';
import NgBackend from './NgBackend';
import NgClientCredentialTokenEndpoint from './NgClientCredentialTokenEndpoint';
import NgDefaultRequestBody from './NgDefaultRequestBody';
import NgErrorRewriter from './NgErrorRewriter';
import NgFrontend from './NgFrontend';
import NgHttpClientCache from './NgHttpClientCache';
import NgLegacyApikeyCall from './NgLegacyApikeyCall';
import NgLegacyAuthModuleCall from './NgLegacyAuthModuleCall';
import NgHasClientCertValidator from './NgHasClientCertValidator';
import NgHasClientCertMatchingApikeyValidator from './NgHasClientCertMatchingApikeyValidator';
import NgHasClientCertMatchingValidator from './NgHasClientCertMatchingValidator';
import NgClientCertChainHeader from './NgClientCertChainHeader';
import NgCertificateAsApikey from './NgCertificateAsApikey';
import NgBiscuitExtractor from './NgBiscuitExtractor';
import NgBiscuitValidator from './NgBiscuitValidator';
import NgHasClientCertMatchingHttpValidator from './NgHasClientCertMatchingHttpValidator';
import NgHasAllowedUsersValidator from './NgHasAllowedUsersValidator';
import NgUserAgentExtractor from './NgUserAgentExtractor';
import NgUserAgentInfoEndpoint from './NgUserAgentInfoEndpoint';
import NgUserAgentInfoHeader from './NgUserAgentInfoHeader';
import NgSecurityTxt from './NgSecurityTxt';
import NgServiceQuotas from './NgServiceQuotas';
import NgMirroringPlugin from './NgMirroringPlugin';
import NgLog4ShellFilter from './NgLog4ShellFilter';
import NgJwtUserExtractor from './NgJwtUserExtractor';
import NgIzanamiProxy from './NgIzanamiProxy';
import NgIzanamiCanary from './NgIzanamiCanary';
import NgMaxMindGeolocationInfoExtractor from './NgMaxMindGeolocationInfoExtractor';
import NgIpStackGeolocationInfoExtractor from './NgIpStackGeolocationInfoExtractor';
import NgGeolocationInfoHeader from './NgGeolocationInfoHeader';
import NgGeolocationInfoEndpoint from './NgGeolocationInfoEndpoint';
import NgDiscoverySelfRegistrationSink from './NgDiscoverySelfRegistrationSink';
import NgDiscoverySelfRegistrationTransformer from './NgDiscoverySelfRegistrationTransformer';
import NgDiscoveryTargetsSelector from './NgDiscoveryTargetsSelector';
import NgDeferPlugin from './NgDeferPlugin';
import NgClientCredentials from './NgClientCredentials';
import OAuth1Caller from './OAuth1Caller';
import OAuth2Caller from './OAuth2Caller';
import OIDCAccessTokenAsApikey from './OIDCAccessTokenAsApikey';
import OIDCAccessTokenValidator from './OIDCAccessTokenValidator';
import OIDCHeaders from './OIDCHeaders';
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
import ResponseCache from './ResponseCache';
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
import WasmRouteMatcher from './WasmRouteMatcher';
import WasmSink from './WasmSink';
import WasmJob from './WasmJob';
import WasmPreRoute from './WasmPreRoute';
import WasmRouter from './WasmRouter';
import WasmOPA from './WasmOPA';
import ZipFileBackend from './ZipFileBackend';

export const Backend = NgBackend;
export const Frontend = NgFrontend;

const pluginsArray = [
  AdditionalHeadersIn,
  AdditionalHeadersOut,
  AllowHttpMethods,
  ApikeyCalls,
  ApikeyAuthModule,
  ApikeyQuotas,
  AuthModule,
  BasicAuthCaller,
  BuildMode,
  BrotliResponseCompressor,
  CanaryMode,
  ContextValidation,
  NgCorazaWAF,
  Cors,
  DisableHttp10,
  EndlessHttpResponse,
  EurekaServerSink,
  EurekaTarget,
  ExternalEurekaTarget,
  ForceHttpsTraffic,
  ForwardedHeader,
  GlobalMaintenanceMode,
  GlobalPerIpAddressThrottling,
  GlobalThrottling,
  GraphQLBackend,
  GraphQLProxy,
  GraphQLQuery,
  GzipResponseCompressor,
  HMACCaller,
  HMACValidator,
  HeadersValidation,
  HtmlPatcher,
  Http3Switch,
  ImageReplacer,
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
  MultiAuthModule,
  NgAuthModuleExpectedUser,
  NgAuthModuleUserExtractor,
  NgClientCredentialTokenEndpoint,
  NgDefaultRequestBody,
  NgErrorRewriter,
  NgHttpClientCache,
  NgLegacyApikeyCall,
  NgLegacyAuthModuleCall,
  NgHasClientCertValidator,
  NgHasClientCertMatchingApikeyValidator,
  NgHasClientCertMatchingValidator,
  NgClientCertChainHeader,
  NgCertificateAsApikey,
  NgBiscuitExtractor,
  NgBiscuitValidator,
  NgHasClientCertMatchingHttpValidator,
  NgHasAllowedUsersValidator,
  NgUserAgentExtractor,
  NgUserAgentInfoEndpoint,
  NgUserAgentInfoHeader,
  NgSecurityTxt,
  NgServiceQuotas,
  NgMirroringPlugin,
  NgLog4ShellFilter,
  NgJwtUserExtractor,
  NgIzanamiProxy,
  NgIzanamiCanary,
  NgMaxMindGeolocationInfoExtractor,
  NgIpStackGeolocationInfoExtractor,
  NgGeolocationInfoHeader,
  NgGeolocationInfoEndpoint,
  NgDiscoverySelfRegistrationSink,
  NgDiscoverySelfRegistrationTransformer,
  NgDiscoveryTargetsSelector,
  NgDeferPlugin,
  NgClientCredentials,
  OAuth1Caller,
  OAuth2Caller,
  OIDCAccessTokenAsApikey,
  OIDCAccessTokenValidator,
  OIDCHeaders,
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
  ResponseCache,
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
  WasmOPA,
  WasmRequestTransformer,
  WasmResponseTransformer,
  WasmRequestHandler,
  WasmRouteMatcher,
  WasmSink,
  WasmJob,
  WasmPreRoute,
  WasmRouter,
  ZipFileBackend,
];

export function addPluginForm(plugin) {
  pluginsArray.push(plugin);
}

export function getPluginForm(id) {
  pluginsArray.find((p) => p.id === id);
}

export function Plugins() {
  return pluginsArray;
}
