package otoroshi.next.proxy

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

sealed trait ProxyEngineError {
  def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result]
}
object ProxyEngineError {
  // TODO: we need something better that will handler default error return of otoroshi Errors.craftResponseResult
  case class ResultProxyEngineError(result: Result) extends ProxyEngineError {
    override def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result] = result.future
  }
}
