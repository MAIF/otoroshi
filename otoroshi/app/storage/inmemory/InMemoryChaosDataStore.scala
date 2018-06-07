package storage.inmemory

import env.Env
import models.{ChaosDataStore, ServiceDescriptor, SnowMonkeyConfig}
import org.joda.time.DateTime
import storage.RedisLike

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}

class InMemoryChaosDataStore(redisCli: RedisLike, _env: Env) extends ChaosDataStore {

  override def serviceAlreadyOutage(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = ???

  override def serviceOutages(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Int] = ???

  override def groupOutages(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Int] = ???

  override def registerOutage(descriptor: ServiceDescriptor, conf: SnowMonkeyConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    // outageDurationFrom: FiniteDuration && outageDurationTo: FiniteDuration
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val outageDuration = (conf.outageDurationFrom.toMillis + new scala.util.Random().nextInt(conf.outageDurationTo.toMillis.toInt - conf.outageDurationFrom.toMillis.toInt)).millis
    val serviceUntilKey = s"${env.storageRoot}:outage:bydesc:until:${descriptor.id}" // until end of duration
    val serviceCounterKey = s"${env.storageRoot}:outage:bydesc:counter:${descriptor.id}" // until end of day
    val groupCounterKey = s"${env.storageRoot}:outage:bygroup:counter:${descriptor.groupId}" // until end of day
    ???
  }
}
