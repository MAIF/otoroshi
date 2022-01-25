package otoroshi.next.plugins

import otoroshi.models.{ApiKey, ApikeyTuple, JwtInjection}
import otoroshi.next.models.{NgBackend, NgContextualPlugins, NgTarget, NgRoute}
import otoroshi.next.proxy.NgExecutionReport
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

object Keys {
  val ContextualPluginsKey = TypedKey[NgContextualPlugins]("otoroshi.next.core.ContextualPlugins")
  val ReportKey = TypedKey[NgExecutionReport]("otoroshi.next.core.Report")
  val RouteKey = TypedKey[NgRoute]("otoroshi.next.core.Route")
  val BackendKey = TypedKey[NgTarget]("otoroshi.next.core.Backend")
  val PossibleBackendsKey = TypedKey[NgBackend]("otoroshi.next.core.PossibleBackends")
  val PreExtractedApikeyKey = TypedKey[Either[Option[ApiKey], ApiKey]]("otoroshi.next.core.PreExtractedApikey")
  val PreExtractedApikeyTupleKey = TypedKey[ApikeyTuple]("otoroshi.next.core.PreExtractedApikeyTuple")
  val BodyAlreadyConsumedKey = TypedKey[AtomicBoolean]("otoroshi.next.core.BodyAlreadyConsumed")
  val JwtInjectionKey = TypedKey[JwtInjection]("otoroshi.next.core.JwtInjection")
  val ResultTransformerKey = TypedKey[Function[Result, Future[Result]]]("otoroshi.next.core.ResultTransformer")
}
