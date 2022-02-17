package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.models.{RestrictionPath, Restrictions}
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterSyntax}
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgRestrictionPath(method: String, path: String) {
  def json: JsValue           = NgRestrictionPath.format.writes(this)
  def legacy: RestrictionPath = RestrictionPath(method, path)
}

object NgRestrictionPath {
  def fromLegacy(path: RestrictionPath): NgRestrictionPath = NgRestrictionPath(path.method, path.path)
  val format                                               = new Format[NgRestrictionPath] {
    override def writes(o: NgRestrictionPath): JsValue = {
      Json.obj(
        "method" -> o.method,
        "path"   -> o.path
      )
    }
    override def reads(json: JsValue): JsResult[NgRestrictionPath] = {
      Try {
        NgRestrictionPath(
          method = (json \ "method").as[String],
          path = (json \ "path").as[String]
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
  }
}

case class NgRestrictions(
    allowLast: Boolean = true,
    allowed: Seq[NgRestrictionPath] = Seq.empty,
    forbidden: Seq[NgRestrictionPath] = Seq.empty,
    notFound: Seq[NgRestrictionPath] = Seq.empty
) {
  def json: JsValue        = NgRestrictions.format.writes(this)
  def legacy: Restrictions = Restrictions(
    enabled = true,
    allowLast = allowLast,
    allowed = allowed.map(_.legacy),
    forbidden = forbidden.map(_.legacy),
    notFound = notFound.map(_.legacy)
  )
}

object NgRestrictions {
  def fromLegacy(settings: Restrictions): NgRestrictions = NgRestrictions(
    allowLast = settings.allowLast,
    allowed = settings.allowed.map(NgRestrictionPath.fromLegacy),
    forbidden = settings.forbidden.map(NgRestrictionPath.fromLegacy),
    notFound = settings.notFound.map(NgRestrictionPath.fromLegacy)
  )
  val format                                             = new Format[NgRestrictions] {
    override def writes(o: NgRestrictions): JsValue = {
      Json.obj(
        "allow_last" -> o.allowLast,
        "allowed"    -> JsArray(o.allowed.map(_.json)),
        "forbidden"  -> JsArray(o.forbidden.map(_.json)),
        "not_found"  -> JsArray(o.notFound.map(_.json))
      )
    }
    override def reads(json: JsValue): JsResult[NgRestrictions] = {
      Try {
        NgRestrictions(
          allowLast = (json \ "allow_last").asOpt[Boolean].getOrElse(true),
          allowed = (json \ "allowed")
            .asOpt[JsArray]
            .map(_.value.map(p => NgRestrictionPath.format.reads(p)).collect { case JsSuccess(rp, _) =>
              rp
            })
            .getOrElse(Seq.empty),
          forbidden = (json \ "forbidden")
            .asOpt[JsArray]
            .map(_.value.map(p => NgRestrictionPath.format.reads(p)).collect { case JsSuccess(rp, _) =>
              rp
            })
            .getOrElse(Seq.empty),
          notFound = (json \ "not_found")
            .asOpt[JsArray]
            .map(_.value.map(p => NgRestrictionPath.format.reads(p)).collect { case JsSuccess(rp, _) =>
              rp
            })
            .getOrElse(Seq.empty)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
  }
}

class RoutingRestrictions extends NgAccessValidator {

  private val configReads: Reads[NgRestrictions] = NgRestrictions.format

  override def core: Boolean                   = true
  override def name: String                    = "Routing Restrictions"
  override def description: Option[String]     =
    "This plugin apply routing restriction `method domain/path` on the current request/route".some
  override def defaultConfig: Option[JsObject] = NgRestrictions().json.asObject.some
  override def isAccessAsync: Boolean          = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val restrictions                                   = ctx.cachedConfig(internalName)(configReads).getOrElse(NgRestrictions())
    val (restrictionsNotPassing, restrictionsResponse) = restrictions.legacy.handleRestrictions(
      ctx.route.serviceDescriptor.id,
      ctx.route.serviceDescriptor.some,
      None,
      ctx.request,
      ctx.attrs
    )
    if (restrictionsNotPassing) {
      restrictionsResponse.map(r => NgAccess.NgDenied(r))
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}
