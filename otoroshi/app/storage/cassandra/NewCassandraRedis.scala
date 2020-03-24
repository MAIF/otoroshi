package storage.cassandra

import java.util.concurrent.{TimeUnit, _}
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.codahale.metrics._
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, PreparedStatement, Row}
import com.datastax.oss.driver.internal.core.config.typesafe.DefaultDriverConfigLoader
import com.typesafe.config.ConfigFactory
import env.Env
import play.api.{Configuration, Logger}
import storage.{DataStoreHealth, Healthy, RedisLike, Unreachable}
import utils.SchedulerHelper

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NoStackTrace

trait RawGetRedis {
  def rawGet(key: String): Future[Option[(String, Long, Any)]]
}

object CassImplicits {

  implicit class BetterCassString(val s: String) extends AnyVal {
    def escape: String = s.replace("'", "''")
  }

  implicit class EnhancedAsyncResultSet(val rsf: AsyncResultSet) extends AnyVal {

    import collection.JavaConverters._
    import scala.compat.java8.FutureConverters._

    def list()(implicit mat: Materializer): Future[Seq[Row]] = {
      val base = Source(rsf.currentPage().asScala.toList)
      if (rsf.hasMorePages) {
        val more = Source.repeat(())
          .takeWhile(_ => rsf.hasMorePages, true)
          .mapAsync(1) { _ =>
            rsf.fetchNextPage().toScala
          }
          .flatMapConcat(it => Source(it.currentPage().asScala.toList)) // TODO: use it instead of rsf ?
        base.concat(more).runFold(Seq.empty[Row])(_ :+ _)
      } else {
        base.runFold(Seq.empty[Row])(_ :+ _)
      }
    }
  }

  implicit class ConditionalEffect[T](val any: T) extends AnyVal {
    def withCondition(p: => Boolean)(f: T => T): T = p match {
      case true  => f(any)
      case false => any
    }
  }
}

object NewCassandraRedis {
  val logger = Logger("otoroshi-cassandra-datastores")
}

class NewCassandraRedis(actorSystem: ActorSystem, configuration: Configuration)(implicit ec: ExecutionContext, mat: Materializer, env: Env) extends RedisLike with RawGetRedis {

  import CassImplicits._
  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.compat.java8.FutureConverters._


  private val metrics = new MetricRegistry()

  private val patterns = new ConcurrentHashMap[String, Pattern]()

  private val cassandraDurableWrites: String =
    configuration.getOptional[Boolean]("app.cassandra.durableWrites").map(_.toString).getOrElse("true")
  private val cassandraReplicationStrategy: String =
    configuration.getOptional[String]("app.cassandra.replicationStrategy").getOrElse("none")
  private val cassandraReplicationOptions: String =
    configuration.getOptional[String]("app.cassandra.replicationOptions").getOrElse("'dc0': 1")
  private val cassandraReplicationFactor: Int =
    configuration.getOptional[Int]("app.cassandra.replicationFactor").getOrElse(0)


  private val maybeUsername: Option[String] = configuration.getOptional[String]("app.cassandra.username")
  private val maybePassword: Option[String] = configuration.getOptional[String]("app.cassandra.password")
  private val maybeAuthId: Option[String] = configuration.getOptional[String]("app.cassandra.authorizationId")

  private val sessionBuilder = {
    val loader = new DefaultDriverConfigLoader(() => {
        ConfigFactory.invalidateCaches()
        val config = configuration.getOptional[Configuration]("app.cassandra")
          .map(_.underlying)
          .getOrElse(Configuration.empty.underlying)
          .withFallback(ConfigFactory.defaultReference())
          .resolve()
        config
    })
    CqlSession.builder()
      .withConfigLoader(loader)
  }

  private val _session = {
    (maybeUsername, maybePassword, maybeAuthId) match {
      case (Some(username), Some(password), Some(authId)) => sessionBuilder.withAuthCredentials(username, password, authId).build()
      case (Some(username), Some(password), None) => sessionBuilder.withAuthCredentials(username, password).build()
      case _ => sessionBuilder.build()
    }
  }

  private val cancel = new AtomicReference[Cancellable]()

  override def start(): Unit = {
    NewCassandraRedis.logger.info("Creating database keyspace and tables if not exists ...")
    if (cassandraReplicationStrategy == "NetworkTopologyStrategy") {
      _session.execute(
        s"CREATE KEYSPACE IF NOT EXISTS otoroshi WITH replication = {'class':'NetworkTopologyStrategy', $cassandraReplicationOptions} AND DURABLE_WRITES = $cassandraDurableWrites;"
      )
    } else {
      _session.execute(
        s"CREATE KEYSPACE IF NOT EXISTS otoroshi WITH replication = {'class':'SimpleStrategy', 'replication_factor':$cassandraReplicationFactor} AND DURABLE_WRITES = $cassandraDurableWrites;"
      )
    }
    _session.execute("USE otoroshi")
    _session.execute(
      "CREATE TABLE IF NOT EXISTS otoroshi.values ( key text, type text, ttlv text, value text, lvalue list<text>, svalue set<text>, mvalue map<text, text>, PRIMARY KEY (key) );"
    )
    _session.execute("CREATE TABLE IF NOT EXISTS otoroshi.counters ( key text, cvalue counter, PRIMARY KEY (key) );")
    _session.execute("CREATE TABLE IF NOT EXISTS otoroshi.expirations ( key text, value bigint, PRIMARY KEY (key) );")

    cancel.set(actorSystem.scheduler.scheduleAtFixedRate(1.second, 5.seconds)(SchedulerHelper.runnable {
      val time = System.currentTimeMillis()
      executeAsync("SELECT key, value from otoroshi.expirations;").flatMap { rs =>
        rs.list()map(_.foreach { row =>
          val key   = row.getString("key")
          val value = row.getLong("value")
          if (value < time) {
            executeAsync(s"DELETE FROM otoroshi.counters where key = '$key';")
            executeAsync(s"DELETE FROM otoroshi.expirations where key = '$key';")
          }
        })
      }
    }))
    NewCassandraRedis.logger.info("Keyspace and table creation done !")
  }

  override def stop(): Unit = {
    Option(cancel.get()).foreach(_.cancel())
    _session.close()
  }

  private case object CassandraSessionClosed extends RuntimeException("Casssandra session closed") with NoStackTrace

  private val blockAsync         = false
  private val preparedStatements = new TrieMap[String, PreparedStatement]()
  private def executeAsync(query: String, params: Map[String, Any] = Map.empty): Future[AsyncResultSet] = {
    if (_session.isClosed) {
      FastFuture.failed(CassandraSessionClosed)
    } else {
      val readQuery = query.toLowerCase().trim.startsWith("select ")
      val timer     = metrics.timer("cassandra.ops").time()
      val timerOp   = metrics.timer(if (readQuery) "cassandra.reads" else "cassandra.writes").time()
      try {
        val rsf = if (params.isEmpty) {
          _session.executeAsync(query).toScala
        } else {
          val preparedStatement = preparedStatements.getOrElseUpdate(query, _session.prepare(query))
          val bound             = preparedStatement.bind()
          params.foreach { tuple =>
            val key = tuple._1
            tuple._2 match {
              case value: String  => bound.setString(key, value)
              case value: Int     => bound.setInt(key, value)
              case value: Boolean => bound.setBoolean(key, value)
              case value: Long    => bound.setLong(key, value)
              case value: Double  => bound.setDouble(key, value)
              case value =>
                NewCassandraRedis.logger.warn(s"Unknown type for parameter '${key}' of type ${value.getClass.getName}")
            }
          }
          _session.executeAsync(bound).toScala
        }
        rsf.andThen {
          case _ =>
            timer.close()
            timerOp.close()
        }
      } catch {
        case e: Throwable =>
          NewCassandraRedis.logger.error(s"""Cassandra error: ${e.getMessage}. Query was: "$query"""")
          metrics.counter("cassandra.errors").inc()
          FastFuture.failed(e)
      }
    }
  }

  private def getAllKeys(): Future[Seq[String]] =
    for {
      values <- executeAsync("SELECT key from otoroshi.values;")
        .flatMap(_.list().map(_.map(_.getString("key")).toSeq))
      counters <- executeAsync("SELECT key from otoroshi.counters;")
        .flatMap(_.list().map(_.map(_.getString("key")).toSeq))
    } yield values ++ counters

  private def getValueAt(key: String): Future[Option[String]] =
    executeAsync(s"SELECT value from otoroshi.values where key = '$key';").flatMap { rs =>
      Try(rs.one().getString("value")).toOption.flatMap(o => Option(o)) match {
        case Some(v) => FastFuture.successful(Some(v))
        case None =>
          executeAsync(s"SELECT cvalue from otoroshi.counters where key = '$key';").map { r =>
            Try(r.one().getLong("cvalue")).toOption.flatMap(o => Option(o)).map(_.toString)
          }
      }
    }

  private def getTypeAndValueAt(key: String): Future[Option[(String, String)]] =
    executeAsync(s"SELECT value, type from otoroshi.values where key = '$key';").flatMap { rs =>
      Try {
        val row   = rs.one()
        val value = row.getString("value")
        val typ   = row.getString("type")
        Option(typ).flatMap(t => Option(value).map(v => (t, v)))
      }.toOption.flatten match {
        case Some(v) => FastFuture.successful(Some(v))
        case None =>
          executeAsync(s"SELECT cvalue from otoroshi.counters where key = '$key';").map { r =>
            Try(r.one().getLong("cvalue")).toOption.flatMap(o => Option(o)).map(v => ("counter", v.toString))
          }
      }
    }

  private def getCounterAt(key: String): Future[Long] =
    executeAsync(s"SELECT cvalue from otoroshi.counters where key = '$key';").map { rs =>
      Try(rs.one().getLong("cvalue")).toOption.flatMap(o => Option(o)).getOrElse(0L)
    }

  private def getExpirationAt(key: String): Future[Long] = ttl(key).map {
    case -1L => -1L
    case ttl => System.currentTimeMillis() + ttl
  }

  private def getExpirationFromExpirationsTableAt(key: String): Future[Long] =
    executeAsync(s"SELECT value from otoroshi.expirations where key = '$key';").map { rs =>
      Try(rs.one().getLong("value")).toOption.flatMap(o => Option(o)).getOrElse(-1L)
    }

  private def getListAt(key: String): Future[Seq[ByteString]] =
    executeAsync(s"SELECT lvalue from otoroshi.values where key = '$key';").map { rs =>
      Try(rs.one().getList("lvalue", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.map(ByteString.apply).toSeq)
        .getOrElse(Seq.empty[ByteString])
    }

  private def getSetAt(key: String): Future[Set[ByteString]] =
    executeAsync(s"SELECT svalue from otoroshi.values where key = '$key';").map { rs =>
      Try(rs.one().getSet("svalue", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toSet.map((e: String) => ByteString(e)))
        .getOrElse(Set.empty[ByteString])
    }

  private def getMapAt(key: String): Future[Map[String, ByteString]] =
    executeAsync(s"SELECT mvalue from otoroshi.values where key = '$key';").map { rs =>
      Try(rs.one().getMap("mvalue", classOf[String], classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toMap.mapValues(ByteString.apply))
        .getOrElse(Map.empty[String, ByteString])
    }

  private def getCounterOptAt(key: String): Future[Option[Long]] =
    executeAsync(s"SELECT cvalue from otoroshi.counters where key = '$key';").map { rs =>
      Try(rs.one().getLong("cvalue")).toOption.flatMap(o => Option(o))
    }

  private def getListOptAt(key: String): Future[Option[Seq[ByteString]]] =
    executeAsync(s"SELECT lvalue from otoroshi.values where key = '$key';").map { rs =>
      Try(rs.one().getList("lvalue", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.map(ByteString.apply).toSeq)
    }

  private def getSetOptAt(key: String): Future[Option[Set[ByteString]]] =
    executeAsync(s"SELECT svalue from otoroshi.values where key = '$key';").map { rs =>
      Try(rs.one().getSet("svalue", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toSet.map((e: String) => ByteString(e)))
    }

  private def getMapOptAt(key: String): Future[Option[Map[String, ByteString]]] =
    executeAsync(s"SELECT mvalue from otoroshi.values where key = '$key';").map { rs =>
      Try(rs.one().getMap("mvalue", classOf[String], classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toMap.mapValues(ByteString.apply))
    }

  override def flushall(): Future[Boolean] =
    for {
      _ <- executeAsync("TRUNCATE otoroshi.values;")
      _ <- executeAsync("TRUNCATE otoroshi.counters;")
      _ <- executeAsync("TRUNCATE otoroshi.expirations;")
    } yield true

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def rawGet(key: String): Future[Option[(String, Long, Any)]] = {
    for {
      exp         <- getExpirationAt(key)
      typAndValue <- getTypeAndValueAt(key)
    } yield
      (exp, typAndValue) match {
        case (e, Some((t, v))) => Some((t, e, v))
        case _                 => None
      }
  }

  override def get(key: String): Future[Option[ByteString]] = getValueAt(key).map(_.map(ByteString.apply))

  override def set(key: String,
                   value: String,
                   exSeconds: Option[Long] = None,
                   pxMilliseconds: Option[Long] = None): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String,
                     value: ByteString,
                     exSeconds: Option[Long] = None,
                     pxMilliseconds: Option[Long] = None): Future[Boolean] = {
    for {
      a <- executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string', :value);",
        Map("value" -> value.utf8String))
      b <- exSeconds
        .map(_ * 1000)
        .orElse(pxMilliseconds)
        .map(ttl => pexpire(key, ttl))
        .getOrElse(FastFuture.successful(true))
    } yield a.wasApplied() && b
    //((exSeconds, pxMilliseconds) match {
    //  case (Some(seconds), Some(_)) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') USING TTL $seconds;")
    //  case (Some(seconds), None) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') USING TTL $seconds;")
    //  case (None, Some(millis)) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') USING TTL ${millis / 1000};")
    //  case (None, None) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string', '${value.utf8String}');")
    //}).map(r => r.wasApplied())
    //exists(key) flatMap {
    //  case false => {
    //    ((exSeconds, pxMilliseconds) match {
    //      case (Some(seconds), Some(_)) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') IF NOT EXISTS USING TTL $seconds;")
    //      case (Some(seconds), None) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') IF NOT EXISTS USING TTL $seconds;")
    //      case (None, Some(millis)) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') IF NOT EXISTS USING TTL ${millis / 1000};")
    //      case (None, None) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string', '${value.utf8String}') IF NOT EXISTS;")
    //    }).map(r => r.wasApplied())
    //  }
    //  case true => {
    //    ((exSeconds, pxMilliseconds) match {
    //      case (Some(seconds), Some(_)) => executeAsync(s"UPDATE otoroshi.values SET value = '${value.utf8String}' WHERE key = '$key' IF EXISTS USING TTL $seconds;")
    //      case (Some(seconds), None) => executeAsync(s"UPDATE otoroshi.values SET value = '${value.utf8String}' WHERE key = '$key' IF EXISTS USING TTL $seconds;")
    //      case (None, Some(millis)) => executeAsync(s"UPDATE otoroshi.values SET value = '${value.utf8String}' WHERE key = '$key' IF EXISTS USING TTL ${millis / 1000};")
    //      case (None, None) => executeAsync(s"UPDATE otoroshi.values SET value = '${value.utf8String}' WHERE key = '$key' IF EXISTS;")
    //    }).map(r => r.wasApplied())
    //  }
    //}
  }

  override def del(keys: String*): Future[Long] =
    FastFuture
      .sequence(
        keys.map { k =>
          for {
            _ <- executeAsync(s"DELETE FROM otoroshi.values where key = '$k' IF EXISTS;")
            _ <- executeAsync(s"DELETE FROM otoroshi.counters where key = '$k';")
          } yield 1L
        }
      )
      .map(_.foldLeft(0L)(_ + _))

  override def incr(key: String): Future[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): Future[Long] =
    executeAsync(s"UPDATE otoroshi.counters SET cvalue = cvalue + $increment WHERE key = '$key';")
      .flatMap(_ => getCounterAt(key))

  override def exists(key: String): Future[Boolean] = {
    executeAsync(s"SELECT key FROM otoroshi.values WHERE key = '$key' LIMIT 1").flatMap(rs => rs.list().map(_.nonEmpty)).flatMap {
      case true => FastFuture.successful(true)
      case false =>
        executeAsync(s"SELECT key FROM otoroshi.counters WHERE key = '$key' LIMIT 1")
          .flatMap(rs => rs.list().map(_.nonEmpty))
    }
  }

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    FastFuture.sequence(keys.map(k => get(k)))

  override def keys(pattern: String): Future[Seq[String]] = {
    val pat = patterns.computeIfAbsent(pattern, _ => Pattern.compile(pattern.replaceAll("\\*", ".*")))
    getAllKeys().map(_.filter { k =>
      pat.matcher(k).find
    })
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = {
    executeAsync(
      s"UPDATE otoroshi.values SET mvalue = mvalue - {${fields.map(v => s"'$v'").mkString(", ")}} WHERE key = '$key';"
    ).map(_ => fields.size)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = getMapAt(key)

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] =
    executeAsync(s"INSERT INTO otoroshi.values (key, type, mvalue) values ('$key', 'hash', {}) IF NOT EXISTS")
      .flatMap { _ =>
        executeAsync(
          s"UPDATE otoroshi.values SET mvalue = mvalue + {'$field' : '${value.utf8String.escape}' } WHERE key = '$key';"
        ).map(_ => true)
      }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def llen(key: String): Future[Long] =
    getListAt(key).map(_.size)

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] =
    executeAsync(s"INSERT INTO otoroshi.values (key, type, lvalue) values ('$key', 'list', [ ]) IF NOT EXISTS;")
      .flatMap { _ =>
        val list = values.map(_.utf8String.escape).map(v => s"'$v'").mkString(",")
        executeAsync(s"UPDATE otoroshi.values SET lvalue = [ $list ] + lvalue  where key = '$key';")
          .map(_ => values.size)
      }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] =
    getListAt(key).map(_.slice(start.toInt, stop.toInt - start.toInt))

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] =
    getListAt(key).flatMap { list =>
      if (list.nonEmpty) {
        val listStr =
          list.slice(start.toInt, stop.toInt - start.toInt).map(a => s"'${a.utf8String.escape}'").mkString(",")
        executeAsync(s"UPDATE otoroshi.values SET lvalue = [ $listStr ] where key = '$key';")
          .map(_ => true)
      } else {
        FastFuture.successful(true)
      }
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = {
    // executeAsync(s"SELECT ttl(value) as ttl FROM otoroshi.values WHERE key = '$key' LIMIT 1").flatMap { r =>
    //   Try(r.one().getLong("ttl")).toOption.flatMap(o => Option(o)).map(_.toLong) match {
    //     case Some(ttl) => FastFuture.successful(Some(ttl))
    //     case None => getExpirationFromExpirationsTableAt(key).map {
    //       case -1L => None
    //       case v =>
    //         val ttlValue: Long = v - System.currentTimeMillis()
    //         Some(if (ttlValue < 0) -1L else ttlValue)
    //     }
    //   }
    // } map {
    //   case Some(o) => o
    //   case None    => -1L
    // }
    getExpirationFromExpirationsTableAt(key).map {
      case -1L => -1L
      case v =>
        val ttlValue: Long = v - System.currentTimeMillis()
        if (ttlValue < 0) -1L else ttlValue
    }
  }

  override def ttl(key: String): Future[Long] =
    pttl(key).map(t => scala.concurrent.duration.Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = pexpire(key, seconds * 1000)

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    val time = System.currentTimeMillis() + milliseconds
    for {
      //a <- executeAsync(s"UPDATE otoroshi.values USING TTL ${milliseconds / 1000} SET ttlv = null where key = '$key';")
      b <- executeAsync(s"INSERT INTO otoroshi.expirations (key, value) values ('$key', $time);")
    } yield true // a.wasApplied() || b.wasApplied()
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    executeAsync(s"INSERT INTO otoroshi.values (key, type, svalue) values ('$key', 'set', {}) IF NOT EXISTS;")
      .flatMap { _ =>
        executeAsync(
          s"UPDATE otoroshi.values SET svalue = svalue + {${members.map(v => s"'${v.utf8String.escape}'").mkString(", ")}} where key = '$key';"
        ).map(_ => members.size)
      }
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] =
    getSetAt(key).map(_.contains(member))

  override def smembers(key: String): Future[Seq[ByteString]] = getSetAt(key).map(_.toSeq)

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    executeAsync(
      s"UPDATE otoroshi.values SET svalue = svalue - {${members.map(v => s"'${v.utf8String.escape}'").mkString(", ")}} WHERE key = '$key' IF EXISTS;"
    ).map(_ => members.size)
  }

  override def scard(key: String): Future[Long] = {
    // executeAsync(s"SELECT size(svalue) as size FROM otoroshi.values WHERE key = '$key';").map(r => Try(r.one().getLong("size")).toOption.flatMap(o => Option(o)).getOrElse(0))
    smembers(key).map(_.size.toLong) // TODO: find something for that OUTCH !!!
  }

  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    executeAsync("SHOW VERSION").map(_ => Healthy).recover {
      case _ => Unreachable
    }
  }
}
