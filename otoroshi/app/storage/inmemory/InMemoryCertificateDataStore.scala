package storage.inmemory

import java.util.concurrent.atomic.AtomicReference

import akka.actor.Cancellable
import env.Env
import models.Key
import play.api.libs.json.Format
import ssl.{Cert, CertificateDataStore, DynamicSSLEngineProvider}
import storage.{RedisLike, RedisLikeStore}

import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class InMemoryCertificateDataStore(redisCli: RedisLike, _env: Env)
    extends CertificateDataStore
    with RedisLikeStore[Cert] {

  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[Cert]                       = Cert._fmt
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "certs" / id
  override def extractId(value: Cert): String          = value.id

  val lastUpdatedKey = (Key.Empty / _env.storageRoot / "certs-last-updated").key

  val lastUpdatedRef = new AtomicReference[String]("0")
  val cancelRef      = new AtomicReference[Cancellable](null)

  def startSync(): Unit = {
    implicit val ec  = _env.otoroshiExecutionContext
    implicit val env = _env
    cancelRef.set(_env.otoroshiActorSystem.scheduler.schedule(2.seconds, 2.seconds) {
      for {
        certs <- findAll()
        last  <- redisCli.get(lastUpdatedKey).map(_.map(_.utf8String).getOrElse("0"))
      } yield {
        if (last != lastUpdatedRef.get()) {
          lastUpdatedRef.set(last)
          DynamicSSLEngineProvider.setCertificates(certs)
        }
      }
    })
  }

  def stopSync(): Unit = {
    Option(cancelRef.get()).foreach(_.cancel())
  }

  override def delete(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = super.delete(id).andThen {
    case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
  }

  override def delete(value: Cert)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    super.delete(value).andThen {
      case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
    }

  override def deleteAll()(implicit ec: ExecutionContext, env: Env): Future[Long] = super.deleteAll().andThen {
    case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
  }

  override def set(value: Cert, pxMilliseconds: Option[Duration] = None)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Boolean] =
    super.set(value, pxMilliseconds).andThen {
      case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
    }

  override def exists(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = super.exists(id).andThen {
    case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
  }

  override def exists(value: Cert)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    super.exists(value).andThen {
      case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
    }
}
