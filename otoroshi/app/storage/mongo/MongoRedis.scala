package storage.mongo

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import play.api.Logger
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{Cursor, DefaultDB, MongoConnection}
import reactivemongo.bson.{BSONArray, BSONDocument, BSONLong, BSONNull, BSONNumberLike, BSONString}
import storage.{DataStoreHealth, Healthy, RedisLike}

import scala.concurrent.{ExecutionContext, Future}

class MongoRedis(actorSystem: ActorSystem, connection: MongoConnection, dbName: String) extends RedisLike {

  import actorSystem.dispatcher

  lazy val logger = Logger("otoroshi-mongo-redis")

  def database: Future[DefaultDB] = {
    connection.database(dbName)
  }

  def withValuesCollection[A](f: BSONCollection => Future[A]): Future[A] = {
    database.map(_.collection("values")).flatMap(c => f(c))
  }

  // TODO : handle ttl

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)

  override def stop(): Unit = ()

  override def flushall(): Future[Boolean] = database.flatMap(_.drop()).map(_ => true)

  override def get(key: String): Future[Option[ByteString]] = {
    withValuesCollection { coll =>
      coll.find(BSONDocument(
        "key" -> key
      )).one[BSONDocument].map { doc =>
        doc.flatMap(d => d.getAs[String]("value")).map(ByteString.apply)
      }
    }
  }

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> BSONDocument("$in" -> BSONArray(keys.map(BSONString.apply)))))
      .cursor[BSONDocument]().collect[List](-1, Cursor.FailOnError[List[BSONDocument]]())
        .map(list => list.map(i => i.getAs[String]("value").map(ByteString.apply)))
  }

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String, value: ByteString, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = withValuesCollection { coll =>
    val doc = BSONDocument(
      "type" -> "string",
      "key" -> key,
      "value" -> value.utf8String,
      "ttl" -> exSeconds.map(_ * 1000).orElse(pxMilliseconds).map(BSONLong.apply).getOrElse(BSONNull)
    )
    coll.update(
      BSONDocument("key" -> key),
      doc,
      upsert = true
    ).map(_.ok)
  }

  override def del(keys: String*): Future[Long] = withValuesCollection { coll =>
    coll.remove(BSONDocument("key" -> BSONDocument("$in" -> BSONArray(keys.map(BSONString.apply))))).map(_.n)
  }

  override def exists(key: String): Future[Boolean] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].map(_.isDefined)
  }

  override def keys(pattern: String): Future[Seq[String]] = withValuesCollection { coll =>
    coll.find(
      BSONDocument("key" -> BSONDocument("$regex" -> pattern.replaceAll("\\*", ".*"), "$options" -> "i")),
      BSONDocument("key" -> 1)
    ).cursor[BSONDocument]().collect[List](-1, Cursor.FailOnError[List[BSONDocument]]())
      .map(_.map(_.getAs[String]("key").get))
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def incr(key: String): Future[Long] = ???

  override def incrby(key: String, increment: Long): Future[Long] = ???

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = ???

  override def hgetall(key: String): Future[Map[String, ByteString]] = ???

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = withValuesCollection { coll =>
    val doc = BSONDocument(
      "type" -> "hash",
      "key" -> key,
      s"value.$field" -> value.utf8String
    )
    coll.update(
      BSONDocument("key" -> key),
      doc,
      upsert = true
    ).map(_.ok)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def llen(key: String): Future[Long] = ???

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] = lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = ???

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = ???

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = ???

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key))
      .one[BSONDocument]
      .map(_.flatMap(d => d.getAs[Long]("ttl")).map(t => t - System.currentTimeMillis()).filter(_ > -1).getOrElse(-1))
  }

  override def ttl(key: String): Future[Long] = pttl(key).map(t => scala.concurrent.duration.Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = {
    val ttl = System.currentTimeMillis() + (seconds * 1000)
    withValuesCollection { coll =>
      coll.update(
        BSONDocument("key" -> key),
        BSONDocument("ttl" -> ttl)
      ).map(_.ok)
    }
  }

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    val ttl = System.currentTimeMillis() + milliseconds
    withValuesCollection { coll =>
      coll.update(
        BSONDocument("key" -> key),
        BSONDocument("ttl" -> ttl)
      ).map(_.ok)
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = ???

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = ???

  override def smembers(key: String): Future[Seq[ByteString]] = ???

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = ???

  override def scard(key: String): Future[Long] = ???
}
