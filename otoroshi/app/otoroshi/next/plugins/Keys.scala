package otoroshi.next.plugins

import otoroshi.models.{ApiKey, ApikeyTuple, JwtInjection}
import otoroshi.next.models._
import otoroshi.next.proxy.NgExecutionReport
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

object Keys {
  val MatchedRoutesKey: TypedKey[Seq[String]]                                           = TypedKey[Seq[String]]("otoroshi.next.core.MatchedRoutes")
  val ContextualPluginsKey: TypedKey[NgContextualPlugins]                               =
    TypedKey[NgContextualPlugins]("otoroshi.next.core.ContextualPlugins")
  val ReportKey: TypedKey[NgExecutionReport]                                            = TypedKey[NgExecutionReport]("otoroshi.next.core.Report")
  val MatchedRouteKey: TypedKey[NgMatchedRoute]                                         = TypedKey[NgMatchedRoute]("otoroshi.next.core.NgMatchedRoute")
  val RouteKey: TypedKey[NgRoute]                                                       = TypedKey[NgRoute]("otoroshi.next.core.Route")
  val BackendKey: TypedKey[NgTarget]                                                    = TypedKey[NgTarget]("otoroshi.next.core.Backend")
  val PossibleBackendsKey: TypedKey[NgBackend]                                          = TypedKey[NgBackend]("otoroshi.next.core.PossibleBackends")
  val PreExtractedApikeyKey: TypedKey[Either[(Option[ApiKey], Option[String]), ApiKey]] =
    TypedKey[Either[(Option[ApiKey], Option[String]), ApiKey]]("otoroshi.next.core.PreExtractedApikey")
  val PreExtractedApikeyTupleKey: TypedKey[ApikeyTuple]                                 =
    TypedKey[ApikeyTuple]("otoroshi.next.core.PreExtractedApikeyTuple")
  val BodyAlreadyConsumedKey: TypedKey[AtomicBoolean]                                   =
    TypedKey[AtomicBoolean]("otoroshi.next.core.BodyAlreadyConsumed")
  val JwtInjectionKey: TypedKey[JwtInjection]                                           = TypedKey[JwtInjection]("otoroshi.next.core.JwtInjection")
  val ResultTransformerKey: TypedKey[Function[Result, Future[Result]]]                  =
    TypedKey[Function[Result, Future[Result]]]("otoroshi.next.core.ResultTransformer")
  val ResponseAddHeadersKey: TypedKey[Seq[(String, String)]]                            =
    TypedKey[Seq[(String, String)]]("otoroshi.next.core.ResponseAddHeaders")
}
