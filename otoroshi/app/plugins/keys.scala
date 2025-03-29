package otoroshi.plugins

import akka.Done
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.gateway.GwError
import otoroshi.models._
import otoroshi.next.models.NgTarget
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.RequestHeader

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Promise

case class AttributeSetter[A](key: play.api.libs.typedmap.TypedKey[_ <: A], f: JsValue => _ <: A)

object Keys {
  val BackendDurationKey       = TypedKey[Long]("otoroshi.core.BackendDuration")
  val OtoTokenKey              = TypedKey[JsObject]("otoroshi.core.OtoToken")
  val JwtVerifierKey           = TypedKey[JwtVerifier]("otoroshi.core.JwtVerifier")
  val ApiKeyKey                = TypedKey[ApiKey]("otoroshi.core.ApiKey")
  val ApiKeyJwtKey             = TypedKey[JsValue]("otoroshi.core.ApiKeyJwt")
  val ApiKeyRotationKey        = TypedKey[ApiKeyRotationInfo]("otoroshi.core.ApiKeyRotationInfo")
  val ApiKeyRemainingQuotasKey = TypedKey[RemainingQuotas]("otoroshi.core.RemainingQuotas")
  val UserKey                  = TypedKey[PrivateAppsUser]("otoroshi.core.UserKey")
  val GeolocationInfoKey       = TypedKey[JsValue]("otoroshi.plugins.GeolocationInfo")
  val UserAgentInfoKey         = TypedKey[JsValue]("otoroshi.plugins.UserAgentInfo")
  val ExtraAnalyticsDataKey    = TypedKey[JsValue]("otoroshi.plugins.ExtraAnalyticsData")

  val CaptureRequestBodyKey         = TypedKey[ByteString]("otoroshi.core.CaptureRequestBody")
  val RequestTimestampKey           = TypedKey[DateTime]("otoroshi.core.RequestTimestamp")
  val RequestKey                    = TypedKey[RequestHeader]("otoroshi.core.Request")
  val RequestStartKey               = TypedKey[Long]("otoroshi.core.RequestStart")
  val RequestWebsocketKey           = TypedKey[Boolean]("otoroshi.core.RequestWebsocket")
  val RequestCounterInKey           = TypedKey[AtomicLong]("otoroshi.core.RequestCounterIn")
  val RequestCounterOutKey          = TypedKey[AtomicLong]("otoroshi.core.RequestCounterOut")
  val RequestCanaryIdKey            = TypedKey[String]("otoroshi.core.RequestCanaryId")
  val RequestTrackingIdKey          = TypedKey[String]("otoroshi.core.RequestTrackingId")
  val RequestTargetKey              = TypedKey[Target]("otoroshi.core.RequestTarget")
  val RequestNumberKey              = TypedKey[Int]("otoroshi.core.RequestNumber")
  val SnowFlakeKey                  = TypedKey[String]("otoroshi.core.SnowFlake")
  val ElCtxKey                      = TypedKey[Map[String, String]]("otoroshi.core.ElCtx")
  val GatewayEventExtraInfosKey     = TypedKey[JsValue]("otoroshi.core.GatewayEventExtraInfos")
  val PreExtractedRequestTargetKey  = TypedKey[Target]("otoroshi.core.PreExtractedRequestTarget")
  val PreExtractedRequestTargetsKey = TypedKey[Seq[NgTarget]]("otoroshi.core.PreExtractedRequestTargets")
  val GwErrorKey                    = TypedKey[GwError]("otoroshi.core.GwError")
  val StatusOverrideKey             = TypedKey[Int]("otoroshi.core.StatusOverride")
  val MatchedInputTokenKey          = TypedKey[JsValue]("otoroshi.core.MatchedInputToken")
  val MatchedOutputTokenKey         = TypedKey[JsValue]("otoroshi.core.MatchedOutputToken")
  val StrippedPathKey               = TypedKey[String]("otoroshi.core.StrippedPath")
  val ResponseEndPromiseKey         = TypedKey[Promise[Done]]("otoroshi.core.ResponseEndPromise")
  val ForCurrentListenerOnlyKey     = TypedKey[Boolean]("otoroshi.core.ForCurrentListenerOnly")
  val CurrentListenerKey            = TypedKey[String]("otoroshi.core.CurrentListener")
}
