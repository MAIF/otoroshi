package dns

import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import env.Env
import models.{ServiceDescriptor, ServiceDescriptorQuery, ServiceLocation}
import play.api.Logger

import scala.concurrent.Future

object DnsHandlerActor {
  def props(implicit env: Env) = Props(new DnsHandlerActor())
}

class DnsHandlerActor()(implicit env: Env) extends Actor {

  import com.github.mkroli.dns4s.dsl._

  lazy val logger = Logger("otoroshi-local-dns")

  override def receive = {
    case Query(q) ~ Questions(QName(host) ~ TypeA() :: Nil) => {

      implicit lazy val ec = context.dispatcher

      val theSender = sender()

      def noMatch(): Future[Unit] = {
        logger.info(s"No DNS response for `$host")
        theSender ! Response(q) ~ Answers()
        FastFuture.successful(())
      }

      logger.info(s"DNS query on the dev server for `$host`")

      env.datastores.globalConfigDataStore.singleton().map { globalConfig =>
        host match {
          case env.adminApiHost    => theSender ! Response(q) ~ Answers(RRName(host) ~ ARecord("127.0.0.1"))
          case env.backOfficeHost  => theSender ! Response(q) ~ Answers(RRName(host) ~ ARecord("127.0.0.1"))
          case env.privateAppsHost => theSender ! Response(q) ~ Answers(RRName(host) ~ ARecord("127.0.0.1"))
          case _ =>
            ServiceLocation(host, globalConfig) match {
              case None => noMatch()
              case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
                env.datastores.serviceDescriptorDataStore
                  .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, ""))
                  .flatMap {
                    case None => noMatch()
                    case Some(desc) => {
                      logger.info(s"Found one service matching : `${desc.name}`")
                      theSender ! Response(q) ~ Answers(RRName(host) ~ ARecord("127.0.0.1"))
                      FastFuture.successful(())
                    }
                  }
              }
            }
        }
      }
    }
  }
}
