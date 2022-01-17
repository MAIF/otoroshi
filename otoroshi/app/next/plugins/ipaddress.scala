package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.IpFiltering
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.http.HttpEntity
import play.api.mvc.Results.Status
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class IpAddressAllowedList extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val remoteAddress = ctx.request.theIpAddress
    val addresses = ctx.config.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty)
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
          ctx.route.serviceDescriptor.some,
          Some( "errors.ip.address.not.allowed"),
          duration = ctx.report.getDurationNow(), // TODO: checks if it's the rights move
          overhead = ctx.report.getOverheadInNow(), // TODO: checks if it's the rights move
          attrs = ctx.attrs
        ).map(r => NgAccess.NgDenied(r))
    }
  }
}

class IpAddressBlockList extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val remoteAddress = ctx.request.theIpAddress
    val addresses = ctx.config.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty)
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
          ctx.route.serviceDescriptor.some,
          Some( "errors.ip.address.not.allowed"),
          duration = ctx.report.getDurationNow(), // TODO: checks if it's the rights move
          overhead = ctx.report.getOverheadInNow(), // TODO: checks if it's the rights move
          attrs = ctx.attrs
        ).map(r => NgAccess.NgDenied(r))
    }
  }
}

class EndlessHttpResponse extends NgRequestTransformer {
  // TODO: add name and config
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    val remoteAddress = ctx.request.theIpAddress
    val addresses = ctx.config.select("addresses").asOpt[Seq[String]].getOrElse(Seq.empty)
    val finger = ctx.config.select("finger").asOpt[Boolean].getOrElse(false)
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
