package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginHttpResponse, NgRequestTransformer, NgTransformerResponseContext}
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

class GzipResponseCompressor extends NgRequestTransformer {

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    ???
  }
}
