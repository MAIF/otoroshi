package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator}
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class PublicPrivatePaths extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val uri = ctx.request.thePath
    val privatePatterns = ctx.config.select("private_patterns").asOpt[Seq[String]].getOrElse(Seq.empty)
    val publicPatterns = ctx.config.select("public_patterns").asOpt[Seq[String]].getOrElse(Seq.empty)
    val strict = ctx.config.select("strict").asOpt[Boolean].getOrElse(false)
    val isPublic = !privatePatterns.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri)) && publicPatterns.exists(p =>
      otoroshi.utils.RegexPool.regex(p).matches(uri)
    )
    if (isPublic) {
      NgAccess.NgAllowed.vfuture
    } else if (!isPublic && !strict && (ctx.apikey.isDefined || ctx.user.isDefined)) {
      NgAccess.NgAllowed.vfuture
    } else if (!isPublic && strict && ctx.apikey.isDefined) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "Not authorized",
          Results.Unauthorized,
          ctx.request,
          None,
          Some("errors.unauthorized"),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some,
        )
        .map(NgAccess.NgDenied.apply)
    }
  }
}
