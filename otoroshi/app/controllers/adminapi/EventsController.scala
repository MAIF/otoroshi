package controllers.adminapi

import actions.{ApiAction, UnAuthApiAction}
import akka.util.ByteString
import env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}
import utils.{AdminApiHelper, JsonApiError, SendAuditAndAlert}

class EventsController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) with AdminApiHelper {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-events-api")

  def auditEvents() = ApiAction.async { ctx =>
    val options = SendAuditAndAlert("ACCESS_AUDIT_EVENTS", s"User accessed audit events", None, Json.obj(), ctx)
    fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: ByteString) => Json.parse(e.utf8String), options) {
      env.datastores.auditDataStore.findAllRaw().fright[JsonApiError]
    }
    // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    // val paginationPageSize: Int =
    //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    // val paginationPosition = (paginationPage - 1) * paginationPageSize
    // env.datastores.auditDataStore.findAllRaw().map { elems =>
    //   val filtered = elems.drop(paginationPosition).take(paginationPageSize)
    //   Ok.chunked(
    //     Source
    //       .single(ByteString("["))
    //       .concat(
    //         Source
    //           .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
    //           .intersperse(ByteString(","))
    //       )
    //       .concat(Source.single(ByteString("]")))
    //   )
    //     .as("application/json")
    // }
  }

  def alertEvents() = ApiAction.async { ctx =>
    val options = SendAuditAndAlert("ACCESS_ALERT_EVENTS", s"User accessed alert events", None, Json.obj(), ctx)
    fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: ByteString) => Json.parse(e.utf8String), options) {
      env.datastores.alertDataStore.findAllRaw().fright[JsonApiError]
    }
    // val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    // val paginationPageSize: Int =
    //   ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    // val paginationPosition = (paginationPage - 1) * paginationPageSize
    // env.datastores.alertDataStore.findAllRaw().map { elems =>
    //   val filtered = elems.drop(paginationPosition).take(paginationPageSize)
    //   Ok.chunked(
    //     Source
    //       .single(ByteString("["))
    //       .concat(
    //         Source
    //           .apply(scala.collection.immutable.Iterable.empty[ByteString] ++ filtered)
    //           .intersperse(ByteString(","))
    //       )
    //       .concat(Source.single(ByteString("]")))
    //   )
    //     .as("application/json")
    // }
  }

}
