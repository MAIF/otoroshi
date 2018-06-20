package storage.mongo

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{Cursor, DefaultDB, MongoConnection}
import reactivemongo.bson.DefaultBSONHandlers._
import reactivemongo.bson._
import storage.{DataStoreHealth, Healthy, RedisLike}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

object MongoRedis {
  val indexDone = new AtomicBoolean(false)
}

class MongoRedis(actorSystem: ActorSystem, connection: MongoConnection, dbName: String) extends RedisLike {

  import actorSystem.dispatcher

  lazy val logger = Logger("otoroshi-mongo-redis")

  def initIndexes(): Future[Unit] = {
    if (MongoRedis.indexDone.compareAndSet(false, true)) {
      database.flatMap(_.collectionNames).flatMap { names =>
        if (!names.contains("values")) {
          logger.warn("Creating mongo indexes ...")
          for {
            coll <- database.map(_.collection[BSONCollection]("values"))
            _ <- coll.create()
            // _ <- coll.indexesManager.ensure(Index(Seq("key" -> IndexType.Ascending), unique = true))
            _ <- coll.indexesManager.ensure(Index(Seq("key" -> IndexType.Ascending)))
            _ <- coll.indexesManager.ensure(Index(Seq("ttl" -> IndexType.Ascending), options = BSONDocument(
              "expireAfterSeconds" -> 0
            )))
            _ = Logger.warn("Mongo indexes created !")
          } yield ()
        } else {
          FastFuture.successful(())
        }
      }
    } else {
      FastFuture.successful(())
    }
  }

  def database: Future[DefaultDB] = {
    connection.database(dbName)
  }

  def withValuesCollection[A](f: BSONCollection => Future[A]): Future[A] = {
    database.map(_.collection("values")).flatMap(c => f(c).andThen {
      case Failure(e) => logger.error("Error in DB query", e)
    })
  }

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)

  override def stop(): Unit = ()

  override def flushall(): Future[Boolean] = withValuesCollection(_.drop(false)).map(_ => true)

  override def get(key: String): Future[Option[ByteString]] = withValuesCollection { coll =>
    coll.find(BSONDocument(
      "key" -> key
    )).one[BSONDocument].map { doc =>
      doc.flatMap(d => d.getAs[String]("value")).map(ByteString.apply)
    }
  }

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> BSONDocument("$in" -> BSONArray(keys.map(BSONString.apply)))))
      .cursor[BSONDocument]().collect[List](-1, Cursor.FailOnError[List[BSONDocument]]())
        .map(list => list.map(i => i.getAs[String]("value").map(ByteString.apply)))
  }

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String, value: ByteString, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = withValuesCollection { coll =>
    coll.update(
      BSONDocument("key" -> key),
      BSONDocument(
        "type" -> "string",
        "key" -> key,
        "value" -> value.utf8String,
        "ttl" -> exSeconds.map(_ * 1000).orElse(pxMilliseconds).map(BSONDateTime.apply).getOrElse(BSONNull)
      ),
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

  override def incr(key: String): Future[Long] = incrby(key, 1)

  override def incrby(key: String, increment: Long): Future[Long] = withValuesCollection { coll => // OUTCH
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].flatMap {
      case None => coll.insert(BSONDocument(
          "key" -> key,
          "type" -> "counter",
          "value" -> increment,
          "ttl" -> BSONNull
        )).map { _ =>
          increment
        }
      case Some(_) => coll.update(
          BSONDocument("key" -> key),
          BSONDocument("$inc" -> BSONDocument("value" -> increment)),
          upsert = true
        ).flatMap { _ =>
          coll.find(BSONDocument("key" -> key)).one[BSONDocument].map(_.flatMap(_.getAs[Long]("value")).getOrElse(0L))
        }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = withValuesCollection { coll =>
    coll.update(
      BSONDocument("key" -> key),
      BSONDocument(
        "$unset" -> BSONDocument(
          fields.map(field => s"value.$field" -> "")
        )
      ),
      upsert = true
    ).map(_.n)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = withValuesCollection { coll =>
    coll.find(BSONDocument(
      "key" -> key
    )).one[BSONDocument].map { doc =>
      doc.flatMap(d => d.getAs[BSONDocument]("value")).map(_.toMap.mapValues(a => ByteString(a.asInstanceOf[BSONString].value))).getOrElse(Map.empty)
    }
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].flatMap {
      case None => coll.insert(BSONDocument(
          "key" -> key,
          "type" -> "hash",
          "ttl" -> BSONNull,
          "value" -> BSONDocument(
              field -> value.utf8String
            )
          )).map(_.ok)
      case Some(_) =>
        coll.update(
          BSONDocument("key" -> key),
          BSONDocument(
            "type" -> "hash",
            "key" -> key,
            s"value.$field" -> value.utf8String
          ),
          upsert = true
        ).map(_.ok)
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def llen(key: String): Future[Long] = withValuesCollection { coll => // OUTCH
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].map { doc =>
      doc.map(_.getAs[BSONArray]("value").map(_.size).getOrElse(0)).getOrElse(0).toLong
    }
  }

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] = lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].flatMap {
      case None => coll.insert(BSONDocument(
        "key" -> key,
        "type" -> "list",
        "ttl" -> BSONNull,
        "value" -> BSONArray(values.map(s => BSONString.apply(s.utf8String)))
      )).map(_.n)
      case Some(_) => coll.update(
        BSONDocument("key" -> key),
        BSONDocument(
          "$push" -> BSONDocument("value" -> BSONArray(values.map(s => BSONString.apply(s.utf8String))))
        ),
        upsert = true
      ).map(_.n)
    }
  }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].map { opt =>
      opt.flatMap(doc => doc.getAs[BSONArray]("value")).map(arr => arr.elements.map(_.value.asInstanceOf[BSONString].value).map(ByteString.apply))
        .map(seq => seq.slice(start.toInt, stop.toInt - start.toInt))
        .getOrElse(Seq.empty)
    }
  }

  // WARNING : will work only if start == 0, should be good for now
  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = withValuesCollection { coll =>
    coll.update(
      BSONDocument("key" -> key),
      BSONDocument(
        "$push" -> BSONDocument(
          "value" -> BSONDocument(
            "$each" -> BSONArray(),
            "$slice" -> (0 - stop)
          )
        )
      )
    ).map(_.ok)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key))
      .one[BSONDocument]
      .map(_.flatMap(d => d.getAs[Long]("ttl")).map(t => new DateTime(t, DateTimeZone.UTC).getMillis - DateTime.now(DateTimeZone.UTC).getMillis).filter(_ > -1).getOrElse(-1))
  }

  override def ttl(key: String): Future[Long] = pttl(key).map(t => scala.concurrent.duration.Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = pexpire(key, seconds * 1000)

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    val ttl = DateTime.now(DateTimeZone.UTC).plusMillis(milliseconds.toInt).getMillis
    withValuesCollection { coll =>
      coll.findAndUpdate(
        BSONDocument("key" -> key),
        BSONDocument("ttl" -> BSONDateTime(ttl))
      ).map(_.lastError.isEmpty)
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].flatMap {
      case None => coll.insert(BSONDocument(
        "key" -> key,
        "type" -> "set",
        "ttl" -> BSONNull,
        "value" -> BSONArray(members.map(s => BSONString.apply(s.utf8String)))
      )).map(_.n)
      case Some(_) => {
        coll.update(
          BSONDocument("key" -> key),
          BSONDocument(
            "$addToSet" -> BSONDocument("value" -> BSONArray(members.map(s => BSONString.apply(s.utf8String))))),
          upsert = true
        ).map(_.n)
      }
    }
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = smembers(key).map(_.contains(member)) // OUTCH

  override def smembers(key: String): Future[Seq[ByteString]] = withValuesCollection { coll =>
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].map(_.flatMap { doc =>
      doc.getAs[BSONArray]("value").map { arr =>
        arr.elements.map(e => ByteString(e.value.asInstanceOf[BSONString].value))
      }
    }.getOrElse(Seq.empty[ByteString]))
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = withValuesCollection { coll =>
    coll.update(
      BSONDocument("key" -> key),
      BSONDocument("$pull" -> BSONDocument("value" -> BSONArray(members.map(_.utf8String).map(BSONString.apply))))
    ).map(_.n)
  }

  override def scard(key: String): Future[Long] = withValuesCollection { coll => // OUTCH
    coll.find(BSONDocument("key" -> key)).one[BSONDocument].map { doc =>
      doc.map(_.getAs[BSONArray]("value").map(_.size).getOrElse(0)).getOrElse(0).toLong
    }
  }
}

