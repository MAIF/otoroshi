package otoroshi.next.proxy

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

trait NgProxyEngineError {
  def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result]
}
object NgProxyEngineError       {
  case class NgResultProxyEngineError(result: Result) extends NgProxyEngineError {
    override def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result] = result.vfuture
  }
}
