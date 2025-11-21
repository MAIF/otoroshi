package otoroshi.next.proxy

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.given
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

trait NgProxyEngineError  {
  def asResult()(using ec: ExecutionContext, env: Env): Future[Result]
}
object NgProxyEngineError {
  case class NgResultProxyEngineError(result: Result) extends NgProxyEngineError {
    override def asResult()(using ec: ExecutionContext, env: Env): Future[Result] = result.vfuture
  }
}
