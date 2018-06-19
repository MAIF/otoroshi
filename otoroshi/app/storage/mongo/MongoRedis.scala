package storage.mongo

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import play.api.Logger
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{DefaultDB, MongoConnection}
import reactivemongo.bson.BSONDocument
import storage.{DataStoreHealth, Healthy, RedisLike}

import scala.concurrent.{ExecutionContext, Future}

class MongoRedis(actorSystem: ActorSystem, connection: MongoConnection, dbName: String) extends RedisLike {

  import actorSystem.dispatcher

  lazy val logger = Logger("otoroshi-mongo-redis")

  def database: Future[DefaultDB] = {
    connection.database(dbName)
  }

  def valuesCollection: Future[BSONCollection] = {
    database.map(_.collection("values"))
  }

  def setsCollection: Future[BSONCollection] = {
    database.map(_.collection("sets"))
  }

  def listsCollection: Future[BSONCollection] = {
    database.map(_.collection("lists"))
  }

  def hashCollection: Future[BSONCollection] = {
    database.map(_.collection("hashs"))
  }

  def countersCollection: Future[BSONCollection] = {
    database.map(_.collection("counters"))
  }

  // TODO : handle ttl

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)

  override def stop(): Unit = ()

  override def flushall(): Future[Boolean] = database.flatMap(_.drop()).map(_ => true)

  override def get(key: String): Future[Option[ByteString]] = {
    valuesCollection.flatMap(_.find(BSONDocument(
      "key" -> key
    )).one[BSONDocument]).map(_.flatMap(d => d.getAs("value")[String]).map(ByteString.apply))
  }

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = ???

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String, value: ByteString, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = ???

  override def del(keys: String*): Future[Long] = ???

  override def incr(key: String): Future[Long] = ???

  override def incrby(key: String, increment: Long): Future[Long] = ???

  override def exists(key: String): Future[Boolean] = ???

  override def keys(pattern: String): Future[Seq[String]] = ???

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = ???

  override def hgetall(key: String): Future[Map[String, ByteString]] = ???

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, value)

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = ???

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def llen(key: String): Future[Long] = ???

  override def lpush(key: String, values: String*): Future[Long] = ???

  override def lpushLong(key: String, values: Long*): Future[Long] = ???

  override def lpushBS(key: String, values: ByteString*): Future[Long] = ???

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = ???

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = ???

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = ???

  override def ttl(key: String): Future[Long] = ???

  override def expire(key: String, seconds: Int): Future[Boolean] = ???

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = ???

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def sadd(key: String, members: String*): Future[Long] = ???

  override def saddBS(key: String, members: ByteString*): Future[Long] = ???

  override def sismember(key: String, member: String): Future[Boolean] = ???

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = ???

  override def smembers(key: String): Future[Seq[ByteString]] = ???

  override def srem(key: String, members: String*): Future[Long] = ???

  override def sremBS(key: String, members: ByteString*): Future[Long] = ???

  override def scard(key: String): Future[Long] = ???
}
