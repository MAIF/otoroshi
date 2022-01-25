package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.IpFiltering
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterSyntax}
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc.Results.Status
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class IpAddressesConfig(addresses: Seq[String] = Seq.empty) {
  def json: JsValue = IpAddressesConfig.format.writes(this)
}

object IpAddressesConfig {
  val format = new Format[IpAddressesConfig] {
    override def reads(json: JsValue): JsResult[IpAddressesConfig] = Try {
      IpAddressesConfig(
        addresses = json.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: IpAddressesConfig): JsValue = Json.obj("addresses" -> o.addresses)
  }
}

class IpAddressAllowedList extends NgAccessValidator {

  private val configReads: Reads[IpAddressesConfig] = IpAddressesConfig.format

  override def core: Boolean = true
  override def name: String = "IP allowed list"
  override def description: Option[String] = "This plugin verifies the current request ip address is in the allowed list".some
  override def defaultConfig: Option[JsObject] = IpAddressesConfig().json.asObject.some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val remoteAddress = ctx.request.theIpAddress
    // val addresses = ctx.config.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty)
    val IpAddressesConfig(addresses) = ctx.cachedConfig(internalName)(configReads).getOrElse(IpAddressesConfig())
    val shouldPass = if (addresses.nonEmpty) {
      addresses.exists { ip =>
        if (ip.contains("/")) {
          IpFiltering.network(ip).contains(remoteAddress)
        } else {
          otoroshi.utils.RegexPool(ip).matches(remoteAddress)
        }
      }
    } else {
      false
    }
    if (shouldPass) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "Your IP address is not allowed",
          Results.Forbidden,
          ctx.request,
          None,
          Some( "errors.ip.address.not.allowed"),
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some,
        ).map(r => NgAccess.NgDenied(r))
    }
  }
}

class IpAddressBlockList extends NgAccessValidator {

  private val configReads: Reads[IpAddressesConfig] = IpAddressesConfig.format

  override def core: Boolean = true
  override def name: String = "IP block list"
  override def description: Option[String] = "This plugin verifies the current request ip address is not in the blocked list".some
  override def defaultConfig: Option[JsObject] = IpAddressesConfig().json.asObject.some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val remoteAddress = ctx.request.theIpAddress
    // val addresses = ctx.config.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty)
    val IpAddressesConfig(addresses) = ctx.cachedConfig(internalName)(configReads).getOrElse(IpAddressesConfig())
    val shouldNotPass = if (addresses.nonEmpty) {
      addresses.exists { ip =>
        if (ip.contains("/")) {
          IpFiltering.network(ip).contains(remoteAddress)
        } else {
          otoroshi.utils.RegexPool(ip).matches(remoteAddress)
        }
      }
    } else {
      false
    }
    if (!shouldNotPass) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "Your IP address is not allowed",
          Results.Forbidden,
          ctx.request,
          None,
          Some( "errors.ip.address.not.allowed"),
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some,
        ).map(r => NgAccess.NgDenied(r))
    }
  }
}


case class EndlessHttpResponseConfig(finger: Boolean = false, addresses: Seq[String] = Seq.empty) {
  def json: JsValue = EndlessHttpResponseConfig.format.writes(this)
}

object EndlessHttpResponseConfig {
  val format = new Format[EndlessHttpResponseConfig] {
    override def reads(json: JsValue): JsResult[EndlessHttpResponseConfig] = Try {
      EndlessHttpResponseConfig(
        addresses = json.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty),
        finger = json.select("finger").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: EndlessHttpResponseConfig): JsValue = Json.obj("finger" -> o.finger, "addresses" -> o.addresses)
  }
}

class EndlessHttpResponse extends NgRequestTransformer {

  private val configReads: Reads[EndlessHttpResponseConfig] = EndlessHttpResponseConfig.format

  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def name: String = "Endless HTTP responses"
  override def description: Option[String] = "This plugin returns 128 Gb of 0 to the ip addresses is in the list".some
  override def defaultConfig: Option[JsObject] = EndlessHttpResponseConfig().json.asObject.some

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val remoteAddress = ctx.request.theIpAddress
    // val addresses = ctx.config.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty)
    // val finger = ctx.config.select("finger").asOpt[Boolean].getOrElse(false)
    val EndlessHttpResponseConfig(finger, addresses) = ctx.cachedConfig(internalName)(configReads).getOrElse(EndlessHttpResponseConfig())
    val shouldPass = if (addresses.nonEmpty) {
      addresses.exists { ip =>
        if (ip.contains("/")) {
          IpFiltering.network(ip).contains(remoteAddress)
        } else {
          otoroshi.utils.RegexPool(ip).matches(remoteAddress)
        }
      }
    } else {
      false
    }
    if (shouldPass) {
      val gigas: Long            = 128L * 1024L * 1024L * 1024L
      val fingerCharacter        = ByteString.fromString(
        "\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95"
      )
      val zeros = ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val characters: ByteString = if (finger) fingerCharacter else zeros
      val expected: Long = (gigas / characters.size) + 1L
      val result = Status(200)
        .sendEntity(
          HttpEntity.Streamed(
            Source
              .repeat(characters)
              .take(expected), // 128 Go of zeros or fingers
            None,
            Some("application/octet-stream")
          )
        )
      Left(result).vfuture
    } else {
      Right(ctx.otoroshiRequest).vfuture
    }
  }
}
