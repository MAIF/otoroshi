package otoroshi.next.controllers.adminapi

import org.apache.pekko.util.ByteString
import otoroshi.actions.ApiActionContext
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Audit}
import otoroshi.next.models.NgRoute
import otoroshi.next.utils.{TryIt, TryItRequest}
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsNull, JsString, JsValue, Json}
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// the try it endpoints of the admin api: the trial of the backoffice tester, for a route or a route of an api, under
// the rights of the admin api key. The request is sent by the node answering the admin api, to its own gateway port
object AdminApiTryIt {

  val notFound: Result = Results.NotFound(Json.obj("error" -> "route not found"))

  private def badRequest(message: String): Future[Result] =
    Results.BadRequest(Json.obj("error" -> message)).vfuture

  // an empty body is the default trial: a GET on the path of the first domain of the route
  private def body(raw: ByteString): Either[String, JsValue] =
    if (raw.utf8String.trim.isEmpty) Right(Json.obj())
    else Try(Json.parse(raw.utf8String)).toOption.toRight("the body must be a json object")

  private def canPresent(ctx: ApiActionContext[ByteString], request: TryItRequest)(using env: Env): Boolean =
    request.clientCert.forall(id => env.proxyState.certificate(id).exists(cert => ctx.canUserRead(cert)))

  // the route has been found and the caller can read it (or its api)
  def run(ctx: ApiActionContext[ByteString], route: NgRoute)(using env: Env): Future[Result] = {
    given ec: ExecutionContext = env.otoroshiExecutionContext
    body(ctx.request.body).flatMap(TryItRequest.read) match {
      case Left(error)                                 =>
        badRequest(error)
      // a tool has to know its certificate was not presented: a certificate it cannot read is not found, not ignored
      case Right(request) if !canPresent(ctx, request) =>
        badRequest(s"certificate '${request.clientCert.getOrElse("")}' not found")
      case Right(request)                              =>
        // neither the headers nor the body: they usually carry credentials
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_TRYIT",
            "User tried a request on a route",
            ctx.from,
            ctx.ua,
            Json.obj(
              "routeId" -> route.id,
              "apiId"   -> route.apiRef.map(r => JsString(r.id)).getOrElse(JsNull).as[JsValue],
              "method"  -> request.method,
              "path"    -> TryIt.pathOf(Right(route), request)
            )
          )
        )
        TryIt.run(Right(route), request, e => ctx.canUserRead(e)).map {
          case None         => notFound
          case Some(result) => Results.Ok(result)
        }
    }
  }
}
