package otoroshi.next.plugins

import otoroshi.models.{ApiKey, ApikeyTuple, JwtInjection}
import otoroshi.next.models.*
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgExecutionReport
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

object Keys {
  val MatchedRoutesKey           = TypedKey[Seq[String]]("otoroshi.next.core.MatchedRoutes")
  val ContextualPluginsKey       = TypedKey[NgContextualPlugins]("otoroshi.next.core.ContextualPlugins")
  val ReportKey                  = TypedKey[NgExecutionReport]("otoroshi.next.core.Report")
  val MatchedRouteKey            = TypedKey[NgMatchedRoute]("otoroshi.next.core.NgMatchedRoute")
  val RouteKey                   = TypedKey[NgRoute]("otoroshi.next.core.Route")
  val BackendKey                 = TypedKey[NgTarget]("otoroshi.next.core.Backend")
  val PossibleBackendsKey        = TypedKey[NgBackend]("otoroshi.next.core.PossibleBackends")
  val PreExtractedApikeyKey      =
    TypedKey[Either[(Option[ApiKey], Option[String]), ApiKey]]("otoroshi.next.core.PreExtractedApikey")
  val PreExtractedApikeyTupleKey = TypedKey[ApikeyTuple]("otoroshi.next.core.PreExtractedApikeyTuple")
  val BodyAlreadyConsumedKey     = TypedKey[AtomicBoolean]("otoroshi.next.core.BodyAlreadyConsumed")
  val JwtInjectionKey            = TypedKey[JwtInjection]("otoroshi.next.core.JwtInjection")
  val ResultTransformerKey       = TypedKey[Function[Result, Future[Result]]]("otoroshi.next.core.ResultTransformer")
  val ResponseAddHeadersKey      = TypedKey[Seq[(String, String)]]("otoroshi.next.core.ResponseAddHeaders")
  // holds the plugins whose beforeRequest actually ran, so that afterRequest can mirror it exactly.
  // it is a mutable buffer because the plugin chain can be walked more than once per request.
  val CalledBeforeRequestPluginsKey =
    TypedKey[scala.collection.mutable.Buffer[NgPluginWrapper[NgRequestTransformer]]](
      "otoroshi.next.core.CalledBeforeRequestPlugins"
    )
  // state of the ConditionalPlugin instances of a request, keyed by route and wrapper config
  val ConditionalPluginsStateKey        =
    TypedKey[UnboundedTrieMap[String, ConditionalPluginState]]("otoroshi.next.core.ConditionalPluginsState")
  // nesting depth of the ConditionalPlugin delegations, to break a misconfigured cycle
  val ConditionalPluginDepthKey         = TypedKey[Int]("otoroshi.next.core.ConditionalPluginDepth")
  // position reached in backendCallPlugins when a ConditionalPlugin hands over to the next one
  val ConditionalBackendCallPositionKey = TypedKey[Int]("otoroshi.next.core.ConditionalBackendCallPosition")
}
