package otoroshi.actions

import java.util.concurrent.TimeUnit
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{Sink, Source}
import otoroshi.auth.GenericOauth2Module
import otoroshi.cluster._
import otoroshi.env.Env
import otoroshi.models.PrivateAppsUser
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits.BetterSyntax

case class PrivateAppsActionContext[A](
    request: Request[A],
    users: Seq[PrivateAppsUser],
    globalConfig: otoroshi.models.GlobalConfig
) {
  def connected: Boolean              = users.nonEmpty
  def from(implicit env: Env): String = request.theIpAddress
  def ua: String                      = request.theUserAgent
}

class PrivateAppsAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[PrivateAppsActionContext, AnyContent]
    with ActionFunction[Request, PrivateAppsActionContext] {

  implicit lazy val ec = env.otoroshiExecutionContext

  override def invokeBlock[A](
      request: Request[A],
      block: (PrivateAppsActionContext[A]) => Future[Result]
  ): Future[Result] = {

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host

    def perform() = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        val cookies = request.cookies.filter(c => c.name.startsWith("oto-papps-")).toSeq
        val validCookies = cookies.flatMap(env.extractPrivateSessionId)
        if (validCookies.nonEmpty) {
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(s"private apps session checking for ${validCookies.mkString(", ")} - from action")
          Source(validCookies.toList)
            .mapAsync(1) { id =>
              env.datastores.privateAppsUserDataStore.findById(id).map(opt => (id, opt))
            }
            .mapAsync(1) {
              case (_, Some(user)) => {
                user.withAuthModuleConfig(a => GenericOauth2Module.handleTokenRefresh(a, user))
                user.some.vfuture
              }
              case (id, None) if env.clusterConfig.mode == ClusterMode.Worker => {
                if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"private apps session $id not found locally - from action")
                env.clusterAgent.isSessionValid(id, Some(request)).flatMap {
                  case Some(user) => user.save(Duration(user.expiredAt.getMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)).map(_.some)
                  case None => None.vfuture
                }
              }
              case (_, None) => None.vfuture
            }
            .collect {
              case Some(user) => user
            }
            .runWith(Sink.seq)(env.otoroshiMaterializer).flatMap { users =>
              block(PrivateAppsActionContext(request, users, globalConfig))
            }
        } else {
          if (cookies.nonEmpty) {
            block(PrivateAppsActionContext(request, Seq.empty, globalConfig)).fast
              .map { result =>
                val discardingCookies: Seq[DiscardingCookie] = cookies.flatMap { cookie =>
                  env.removePrivateSessionCookiesWithSuffix(host, cookie.name.replace("oto-papps-", ""))
                }
                result.discardingCookies(discardingCookies: _*)
              }
          } else {
            block(PrivateAppsActionContext(request, Seq.empty, globalConfig))
          }
        }
      }
    }

    host match {
      case env.privateAppsHost                     => perform()
      case h if env.privateAppsDomains.contains(h) => perform()
      case _                                       => {
        // TODO : based on Accept header
        FastFuture.successful(Results.NotFound(otoroshi.views.html.oto.error("Not found", env)))
      }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}
