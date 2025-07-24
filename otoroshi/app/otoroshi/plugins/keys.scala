package otoroshi.plugins

import org.apache.pekko.Done
import org.apache.pekko.util.ByteString
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
  val BackendDurationKey: TypedKey[Long]                  = TypedKey[Long]("otoroshi.core.BackendDuration")
  val OtoTokenKey: TypedKey[JsObject]                     = TypedKey[JsObject]("otoroshi.core.OtoToken")
  val JwtVerifierKey: TypedKey[JwtVerifier]               = TypedKey[JwtVerifier]("otoroshi.core.JwtVerifier")
  val ApiKeyKey: TypedKey[ApiKey]                         = TypedKey[ApiKey]("otoroshi.core.ApiKey")
  val ApiKeyJwtKey: TypedKey[JsValue]                     = TypedKey[JsValue]("otoroshi.core.ApiKeyJwt")
  val ApiKeyRotationKey: TypedKey[ApiKeyRotationInfo]     = TypedKey[ApiKeyRotationInfo]("otoroshi.core.ApiKeyRotationInfo")
  val ApiKeyRemainingQuotasKey: TypedKey[RemainingQuotas] = TypedKey[RemainingQuotas]("otoroshi.core.RemainingQuotas")
  val UserKey: TypedKey[PrivateAppsUser]                  = TypedKey[PrivateAppsUser]("otoroshi.core.UserKey")
  val GeolocationInfoKey: TypedKey[JsValue]               = TypedKey[JsValue]("otoroshi.plugins.GeolocationInfo")
  val UserAgentInfoKey: TypedKey[JsValue]                 = TypedKey[JsValue]("otoroshi.plugins.UserAgentInfo")
  val ExtraAnalyticsDataKey: TypedKey[JsObject]           = TypedKey[JsObject]("otoroshi.plugins.ExtraAnalyticsData")

  val CaptureRequestBodyKey: TypedKey[ByteString]            = TypedKey[ByteString]("otoroshi.core.CaptureRequestBody")
  val RequestTimestampKey: TypedKey[DateTime]                = TypedKey[DateTime]("otoroshi.core.RequestTimestamp")
  val RequestKey: TypedKey[RequestHeader]                    = TypedKey[RequestHeader]("otoroshi.core.Request")
  val RequestStartKey: TypedKey[Long]                        = TypedKey[Long]("otoroshi.core.RequestStart")
  val RequestWebsocketKey: TypedKey[Boolean]                 = TypedKey[Boolean]("otoroshi.core.RequestWebsocket")
  val RequestCounterInKey: TypedKey[AtomicLong]              = TypedKey[AtomicLong]("otoroshi.core.RequestCounterIn")
  val RequestCounterOutKey: TypedKey[AtomicLong]             = TypedKey[AtomicLong]("otoroshi.core.RequestCounterOut")
  val RequestCanaryIdKey: TypedKey[String]                   = TypedKey[String]("otoroshi.core.RequestCanaryId")
  val RequestTrackingIdKey: TypedKey[String]                 = TypedKey[String]("otoroshi.core.RequestTrackingId")
  val RequestTargetKey: TypedKey[Target]                     = TypedKey[Target]("otoroshi.core.RequestTarget")
  val RequestNumberKey: TypedKey[Int]                        = TypedKey[Int]("otoroshi.core.RequestNumber")
  val SnowFlakeKey: TypedKey[String]                         = TypedKey[String]("otoroshi.core.SnowFlake")
  val ElCtxKey: TypedKey[Map[String, String]]                = TypedKey[Map[String, String]]("otoroshi.core.ElCtx")
  val GatewayEventExtraInfosKey: TypedKey[JsObject]          = TypedKey[JsObject]("otoroshi.core.GatewayEventExtraInfos")
  val PreExtractedRequestTargetKey: TypedKey[Target]         = TypedKey[Target]("otoroshi.core.PreExtractedRequestTarget")
  val PreExtractedRequestTargetsKey: TypedKey[Seq[NgTarget]] =
    TypedKey[Seq[NgTarget]]("otoroshi.core.PreExtractedRequestTargets")
  val GwErrorKey: TypedKey[GwError]                          = TypedKey[GwError]("otoroshi.core.GwError")
  val StatusOverrideKey: TypedKey[Int]                       = TypedKey[Int]("otoroshi.core.StatusOverride")
  val MatchedInputTokenKey: TypedKey[JsValue]                = TypedKey[JsValue]("otoroshi.core.MatchedInputToken")
  val MatchedOutputTokenKey: TypedKey[JsValue]               = TypedKey[JsValue]("otoroshi.core.MatchedOutputToken")
  val StrippedPathKey: TypedKey[String]                      = TypedKey[String]("otoroshi.core.StrippedPath")
  val ResponseEndPromiseKey: TypedKey[Promise[Done]]         = TypedKey[Promise[Done]]("otoroshi.core.ResponseEndPromise")
  val ForCurrentListenerOnlyKey: TypedKey[Boolean]           = TypedKey[Boolean]("otoroshi.core.ForCurrentListenerOnly")
  val CurrentListenerKey: TypedKey[String]                   = TypedKey[String]("otoroshi.core.CurrentListener")
}
