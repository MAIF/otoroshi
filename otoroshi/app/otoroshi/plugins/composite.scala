package otoroshi.plugins.composite

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.script.{
  AccessContext,
  AccessValidator,
  HttpRequest,
  HttpResponse,
  PluginType,
  PreRouting,
  PreRoutingContext,
  RequestTransformer,
  TransformerErrorContext,
  TransformerRequestBodyContext,
  TransformerRequestContext,
  TransformerResponseBodyContext,
  TransformerResponseContext
}
import play.api.mvc.Result
import otoroshi.utils.syntax.implicits._
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

// MIGRATED: the new plugin system can handle that natively
class CompositePlugin extends PreRouting with AccessValidator with RequestTransformer {

  val logger: Logger = Logger("CompositePlugin")

  override def deprecated: Boolean = true

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory] = Seq.empty
  override def steps: Seq[NgStep]                = Seq.empty

  override def pluginType: PluginType = PluginType.CompositeType

  override def preRoute(ctx: PreRoutingContext)(using env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"pre-route: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    ().future
  }

  override def canAccess(ctx: AccessContext)(using env: Env, ec: ExecutionContext): Future[Boolean] = {
    logger.info(s"can-access: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    true.future
  }

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    logger.info(s"transform-req: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    Right(ctx.otoroshiRequest).future
  }

  override def transformRequestBodyWithCtx(
      ctx: TransformerRequestBodyContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, ?] = {
    logger.info(s"transform-req-body: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    ctx.body
  }

  override def transformResponseBodyWithCtx(
      ctx: TransformerResponseBodyContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, ?] = {
    logger.info(s"transform-res-body: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    ctx.body
  }

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    logger.info(s"transform-res: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    Right(ctx.otoroshiResponse).future
  }

  override def transformErrorWithCtx(
      ctx: TransformerErrorContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    logger.info(s"transform-err: ${ctx.request.method} ${ctx.request.domain}${ctx.request.path}")
    super.transformErrorWithCtx(ctx)
  }
}
