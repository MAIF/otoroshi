package otoroshi.script

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import env.Env
import gateway.Errors
import models._
import org.apache.commons.codec.binary.Hex
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc.{RequestHeader, Result, Results}
import ssl.{ClientCertificateValidator, PemHeaders}
import utils.{RegexPool, TypedMap}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class AccessValidatorRef(enabled: Boolean = false,
                              excludedPatterns: Seq[String] = Seq.empty[String],
                              refs: Seq[String] = Seq.empty,
                              config: JsValue = Json.obj()) {
  def json: JsValue = AccessValidatorRef.format.writes(this)
}

object AccessValidatorRef {
  val format = new Format[AccessValidatorRef] {
    override def writes(o: AccessValidatorRef): JsValue = Json.obj(
      "enabled"          -> o.enabled,
      "refs"             -> JsArray(o.refs.map(JsString.apply)),
      "config"           -> o.config,
      "excludedPatterns" -> JsArray(o.excludedPatterns.map(JsString.apply)),
    )
    override def reads(json: JsValue): JsResult[AccessValidatorRef] =
      Try {
        JsSuccess(
          AccessValidatorRef(
            refs = (json \ "refs")
              .asOpt[Seq[String]]
              .orElse((json \ "ref").asOpt[String].map(r => Seq(r)))
              .getOrElse(Seq.empty),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            config = (json \ "config").asOpt[JsValue].getOrElse(Json.obj()),
            excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover {
        case e => JsError(e.getMessage)
      } get
  }
}

sealed trait Access
case object Allowed               extends Access
case class Denied(result: Result) extends Access

trait AccessValidator extends StartableAndStoppable with NamedPlugin with InternalEventListener {
  final def pluginType: PluginType = AccessValidatorType
  def access(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Access] = {
    canAccess(context)(env, ec).flatMap {
      case true => FastFuture.successful(Allowed)
      case false =>
        Errors
          .craftResponseResult(
            "bad request",
            Results.BadRequest,
            context.request,
            None,
            None,
            attrs = context.attrs
          )
          .map(Denied.apply)
    }
  }
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean]
}

case class AccessContext(
    snowflake: String,
    index: Int,
    request: RequestHeader,
    descriptor: ServiceDescriptor,
    user: Option[PrivateAppsUser],
    apikey: Option[ApiKey],
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue
) {
  def conf[A](prefix: String = "config-"): Option[JsValue] = {
    config match {
      case json: JsArray  => Option(json.value(index)).orElse((config \ s"$prefix$index").asOpt[JsValue])
      case json: JsObject => (json \ s"$prefix$index").asOpt[JsValue]
      case _              => None
    }
  }
  def confAt[A](key: String, prefix: String = "config-")(implicit fjs: Reads[A]): Option[A] = {
    val conf = config match {
      case json: JsArray  => Option(json.value(index)).getOrElse((config \ s"$prefix$index").as[JsValue])
      case json: JsObject => (json \ s"$prefix$index").as[JsValue]
      case _              => Json.obj()
    }
    (conf \ key).asOpt[A]
  }
}

object DefaultValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    FastFuture.successful(true)
  }
}

object CompilingValidator extends AccessValidator {
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    FastFuture.successful(false)
  }
}
