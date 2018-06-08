package storage.inmemory

import env.Env
import models.{ChaosDataStore, ServiceDescriptor, SnowMonkeyConfig}
import org.joda.time.DateTime
import storage.RedisLike

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class InMemoryChaosDataStore(redisCli: RedisLike, _env: Env) extends ChaosDataStore {

  override def serviceAlreadyOutage(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    redisCli.get(s"${env.storageRoot}:outage:bydesc:until:$serviceId").map(_.isDefined)
  }

  override def serviceOutages(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Int] = {
    redisCli.get(s"${env.storageRoot}:outage:bydesc:counter:$serviceId").map(_.map(_.utf8String.toInt).getOrElse(0))
  }

  override def groupOutages(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Int] = {
    redisCli.get(s"${env.storageRoot}:outage:bygroup:counter:$groupId").map(_.map(_.utf8String.toInt).getOrElse(0))
  }

  override def registerOutage(
      descriptor: ServiceDescriptor,
      conf: SnowMonkeyConfig
  )(implicit ec: ExecutionContext, env: Env): Future[FiniteDuration] = {
    val dayEnd = System.currentTimeMillis() - DateTime.now().millisOfDay().withMaximumValue().getMillis
    val outageDuration = (conf.outageDurationFrom.toMillis + new scala.util.Random()
      .nextInt(conf.outageDurationTo.toMillis.toInt - conf.outageDurationFrom.toMillis.toInt)).millis
    val serviceUntilKey   = s"${env.storageRoot}:outage:bydesc:until:${descriptor.id}"         // until end of duration
    val serviceCounterKey = s"${env.storageRoot}:outage:bydesc:counter:${descriptor.id}"       // until end of day
    val groupCounterKey   = s"${env.storageRoot}:outage:bygroup:counter:${descriptor.groupId}" // until end of day
    for {
      _ <- redisCli.incr(groupCounterKey)
      _ <- redisCli.incr(serviceCounterKey)
      _ <- redisCli.set(serviceUntilKey,
                        DateTime.now().plusMillis(outageDuration.toMillis.toInt).toLocalTime.toString,
                        pxMilliseconds = Some(outageDuration.toMillis))
      _ <- redisCli.pexpire(serviceCounterKey, dayEnd)
      _ <- redisCli.pexpire(groupCounterKey, dayEnd)
      _ <- redisCli.pexpire(groupCounterKey, dayEnd)
    } yield outageDuration
  }

  override def resetOutages()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      uKeys <- redisCli.keys(s"${env.storageRoot}:outage:bydesc:until:*")
      sKeys <- redisCli.keys(s"${env.storageRoot}:outage:bydesc:counter:*")
      gKeys <- redisCli.keys(s"${env.storageRoot}:outage:bygroup:counter:*")
      _     <- redisCli.del((Seq.empty ++ uKeys ++ sKeys ++ gKeys): _*)
    } yield ()
  }

  override def startSnowMonkey()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- env.datastores.chaosDataStore.resetOutages()
      c <- env.datastores.globalConfigDataStore.singleton()
      _ <- c.copy(snowMonkeyConfig = c.snowMonkeyConfig.copy(enabled = true)).save()
    } yield ()
  }

  override def stopSnowMonkey()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- env.datastores.chaosDataStore.resetOutages()
      c <- env.datastores.globalConfigDataStore.singleton()
      _ <- c.copy(snowMonkeyConfig = c.snowMonkeyConfig.copy(enabled = false)).save()
    } yield ()
  }
}
