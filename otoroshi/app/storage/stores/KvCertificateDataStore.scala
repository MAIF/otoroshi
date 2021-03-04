package otoroshi.storage.stores

import java.util.concurrent.atomic.AtomicReference
import akka.actor.Cancellable
import otoroshi.env.Env
import otoroshi.models.Key
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils
import otoroshi.utils.{SchedulerHelper, future}
import otoroshi.utils.letsencrypt.LetsEncryptHelper
import play.api.Logger
import play.api.libs.json.Format
import otoroshi.ssl.{Cert, CertificateDataStore, DynamicSSLEngineProvider}

import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{ExecutionContext, Future}

class KvCertificateDataStore(redisCli: RedisLike, _env: Env)
    extends CertificateDataStore
    with RedisLikeStore[Cert] {

  val logger = Logger("otoroshi-certificate-datastore")

  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[Cert]                       = Cert._fmt
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "certs" / id
  override def extractId(value: Cert): String          = value.id

  val lastUpdatedKey = (Key.Empty / _env.storageRoot / "certs-last-updated").key

  val lastUpdatedRef  = new AtomicReference[String]("0")
  val cancelRef       = new AtomicReference[Cancellable](null)
  val cancelRenewRef  = new AtomicReference[Cancellable](null)
  val cancelCreateRef = new AtomicReference[Cancellable](null)

  def startSync(): Unit = {
    implicit val ec  = _env.otoroshiExecutionContext
    implicit val mat = _env.otoroshiMaterializer
    implicit val env = _env
    importInitialCerts(logger)
    cancelRenewRef.set(
      _env.otoroshiActorSystem.scheduler.scheduleAtFixedRate(60.seconds, 1.hour + ((Math.random() * 10) + 1).minutes)(SchedulerHelper.runnable {
        _env.datastores.certificatesDataStore.renewCertificates()
      })
    )
    cancelCreateRef.set(_env.otoroshiActorSystem.scheduler.scheduleAtFixedRate(60.seconds, 2.minutes)(utils.SchedulerHelper.runnable {
      LetsEncryptHelper.createFromServices()
      Cert.createFromServices()
    }))
    cancelRef.set(_env.otoroshiActorSystem.scheduler.scheduleAtFixedRate(2.seconds, 2.seconds)(utils.SchedulerHelper.runnable {
      for {
        certs <- findAll()
        last  <- redisCli.get(lastUpdatedKey).map(_.map(_.utf8String).getOrElse("0"))
      } yield {
        if (last != lastUpdatedRef.get()) {
          lastUpdatedRef.set(last)
          DynamicSSLEngineProvider.setCertificates(certs, env)
        }
      }
    }))
  }

  def stopSync(): Unit = {
    Option(cancelCreateRef.get()).foreach(_.cancel())
    Option(cancelRenewRef.get()).foreach(_.cancel())
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
