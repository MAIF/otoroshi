package otoroshi.plugins.composite

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.script.{AccessContext, AccessValidator, HttpRequest, HttpResponse, PluginType, PreRouting, PreRoutingContext, RequestTransformer, TransformerErrorContext, TransformerRequestBodyContext, TransformerRequestContext, TransformerResponseBodyContext, TransformerResponseContext}
import play.api.mvc.Result
import otoroshi.utils.syntax.implicits._
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class CompositePlugin extends PreRouting with AccessValidator with RequestTransformer {

  val logger = Logger("CompositePlugin")

  override def pluginType: PluginType = PluginType.CompositeType

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"pre-route: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    ().future
  }

  override def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    logger.info(s"can-access: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    true.future
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    logger.info(s"transform-req: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    Right(ctx.otoroshiRequest).future
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    logger.info(s"transform-req-body: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    ctx.body
  }

  override def transformResponseBodyWithCtx(ctx: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    logger.info(s"transform-res-body: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    ctx.body
  }

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    logger.info(s"transform-res: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    Right(ctx.otoroshiResponse).future
  }

  override def transformErrorWithCtx(ctx: TransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    logger.info(s"transform-err: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    super.transformErrorWithCtx(ctx)
  }
}
