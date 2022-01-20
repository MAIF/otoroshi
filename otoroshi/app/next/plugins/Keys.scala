package otoroshi.next.plugins

import otoroshi.models.{ApiKey, ApikeyTuple, JwtInjection}
import otoroshi.next.models.{NgTarget, Backend, Route}
import otoroshi.next.proxy.ExecutionReport
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

object Keys {
  val ReportKey = TypedKey[ExecutionReport]("otoroshi.next.core.Report")
  val RouteKey = TypedKey[Route]("otoroshi.next.core.Route")
  val BackendKey = TypedKey[NgTarget]("otoroshi.next.core.Backend")
  val PossibleBackendsKey = TypedKey[Backend]("otoroshi.next.core.PossibleBackends")
  val PreExtractedApikeyKey = TypedKey[Either[Option[ApiKey], ApiKey]]("otoroshi.next.core.PreExtractedApikey")
  val PreExtractedApikeyTupleKey = TypedKey[ApikeyTuple]("otoroshi.next.core.PreExtractedApikeyTuple")
  val BodyAlreadyConsumedKey = TypedKey[AtomicBoolean]("otoroshi.next.core.BodyAlreadyConsumed")
  val JwtInjectionKey = TypedKey[JwtInjection]("otoroshi.next.core.JwtInjection")
  val ResultTransformerKey = TypedKey[Function[Result, Future[Result]]]("otoroshi.next.core.ResultTransformer")
}
