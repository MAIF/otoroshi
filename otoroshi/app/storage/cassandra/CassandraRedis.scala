package storage.cassandra

import java.util.concurrent._
import java.util.regex.Pattern

import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.datastax.driver.core.exceptions.InvalidQueryException
import play.api.Configuration
import storage.{DataStoreHealth, Healthy, RedisLike, Unreachable}
import com.codahale.metrics._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Success, Try}

class CassandraRedisNew(actorSystem: ActorSystem, configuration: Configuration)  extends RedisLike with RawGetRedis {

  import Implicits._
  import actorSystem.dispatcher
  import com.datastax.driver.core._

  import collection.JavaConverters._

  private val metrics = new MetricRegistry()

  private val reporter: ConsoleReporter = ConsoleReporter.forRegistry(metrics).convertRatesTo(TimeUnit.SECONDS).convertDurationsTo(TimeUnit.MILLISECONDS).build

  private val patterns = new ConcurrentHashMap[String, Pattern]()

  private val cassandraContactPoints: Seq[String] = configuration
    .getOptional[String]("app.cassandra.hosts")
    .map(_.split(",").toSeq)
    .orElse(
      configuration.getOptional[String]("app.cassandra.host").map(e => Seq(e))
    )
    .getOrElse(Seq("127.0.0.1"))
  private val cassandraReplicationStrategy: String =
    configuration.getOptional[String]("app.cassandra.replicationStrategy").getOrElse("SimpleStrategy")
  private val cassandraReplicationOptions: String =
    configuration.getOptional[String]("app.cassandra.replicationOptions").getOrElse("'dc0': 1")
  private val cassandraReplicationFactor: Int =
    configuration.getOptional[Int]("app.cassandra.replicationFactor").getOrElse(1)
  private val cassandraPort: Int       = configuration.getOptional[Int]("app.cassandra.port").getOrElse(9042)
  private val maybeUsername: Option[String] = configuration.getOptional[String]("app.cassandra.username")
  private val maybePassword: Option[String] = configuration.getOptional[String]("app.cassandra.password")

  private val poolingExecutor: Executor = configuration.getOptional[Int]("app.cassandra.pooling.initializationExecutorThreads").map(n => Executors.newFixedThreadPool(n)).getOrElse(actorSystem.dispatcher)
  private val poolingOptions = new PoolingOptions()
    .setMaxQueueSize(configuration.getOptional[Int]("app.cassandra.pooling.maxQueueSize").getOrElse(2048))
    .setCoreConnectionsPerHost(HostDistance.LOCAL, configuration.getOptional[Int]("app.cassandra.pooling.coreConnectionsPerLocalHost").getOrElse(1))
    .setMaxConnectionsPerHost(HostDistance.LOCAL, configuration.getOptional[Int]("app.cassandra.pooling.coreConnectionsPerRemoteHost").getOrElse(1))
    .setCoreConnectionsPerHost(HostDistance.REMOTE, configuration.getOptional[Int]("app.cassandra.pooling.maxConnectionsPerLocalHost").getOrElse(1))
    .setMaxConnectionsPerHost(HostDistance.REMOTE, configuration.getOptional[Int]("app.cassandra.pooling.maxConnectionsPerRemoteHost").getOrElse(1))
    .setMaxRequestsPerConnection(HostDistance.LOCAL, configuration.getOptional[Int]("app.cassandra.pooling.maxRequestsPerLocalConnection").getOrElse(32768))
    .setMaxRequestsPerConnection(HostDistance.REMOTE, configuration.getOptional[Int]("app.cassandra.pooling.maxRequestsPerRemoteConnection").getOrElse(2048))
    .setNewConnectionThreshold(HostDistance.LOCAL, configuration.getOptional[Int]("app.cassandra.pooling.newLocalConnectionThreshold").getOrElse(30000))
    .setNewConnectionThreshold(HostDistance.REMOTE, configuration.getOptional[Int]("app.cassandra.pooling.newRemoteConnectionThreshold").getOrElse(400))
    .setPoolTimeoutMillis(configuration.getOptional[Int]("app.cassandra.pooling.poolTimeoutMillis").getOrElse(0))
    .setInitializationExecutor(poolingExecutor)
    .setHeartbeatIntervalSeconds(configuration.getOptional[Int]("app.cassandra.pooling.heartbeatIntervalSeconds").getOrElse(30))
    .setIdleTimeoutSeconds(configuration.getOptional[Int]("app.cassandra.pooling.idleTimeoutSeconds").getOrElse(120))

  private val clusterBuilder = Cluster
    .builder()
    .withClusterName(configuration.getOptional[String]("app.cassandra.clusterName").getOrElse("otoroshi-cluster"))
    .addContactPoints(cassandraContactPoints: _*)
    .withPort(cassandraPort)
    .withCondition(configuration.getOptional[Boolean]("app.cassandra.betaProtocolsEnabled").getOrElse(false))(_.allowBetaProtocolVersion())
    .withProtocolVersion(configuration.getOptional[String]("app.cassandra.protocol").map(v => ProtocolVersion.valueOf(v)).getOrElse(ProtocolVersion.V4))
    .withMaxSchemaAgreementWaitSeconds(configuration.getOptional[Int]("app.cassandra.maxSchemaAgreementWaitSeconds").getOrElse(10))
    .withCondition(!configuration.getOptional[Boolean]("app.cassandra.compactEnabled").getOrElse(false))(_.withNoCompact())
    .withCondition(!configuration.getOptional[Boolean]("app.cassandra.metricsEnabled").getOrElse(false))(_.withoutMetrics())
    .withCondition(!configuration.getOptional[Boolean]("app.cassandra.jmxReportingEnabled").getOrElse(false))(_.withoutJMXReporting())
    .withCondition(configuration.getOptional[Boolean]("app.cassandra.sslEnabled").getOrElse(false))(_.withSSL())
    .withCompression(ProtocolOptions.Compression.valueOf(configuration.getOptional[String]("app.cassandra.compression").getOrElse("NONE")))
    .withThreadingOptions(new CassandraThreadingOptions(configuration))
    .withPoolingOptions(poolingOptions)
  // .withLoadBalancingPolicy(LoadBalancingPolicy)
  // .withAuthProvider(AuthProvider authProvider)
  // .withCodecRegistry(CodecRegistry codecRegistry)
  // .withLoadBalancingPolicy(LoadBalancingPolicy policy)
  // .withReconnectionPolicy(ReconnectionPolicy policy)
  // .withRetryPolicy(RetryPolicy policy)
  // .withSocketOptions(SocketOptions options)
  // .withQueryOptions(QueryOptions options)
  // .withNettyOptions(NettyOptions nettyOptions)
  // .withAddressTranslator(AddressTranslator translator)
  // .withTimestampGenerator(TimestampGenerator timestampGenerator)
  // .withSpeculativeExecutionPolicy(SpeculativeExecutionPolicy policy)

  private val cluster: Cluster = (for {
    username <- maybeUsername
    password <- maybePassword
  } yield {
    clusterBuilder.withCredentials(username, password)
  }).getOrElse(clusterBuilder).build()

  private val _session = cluster.connect()

  private val cancel = new AtomicReference[Cancellable]()

  override def start(): Unit = {
    CassandraRedis.logger.info("Creating database keyspace and tables if not exists ...")
    if (cassandraReplicationStrategy == "NetworkTopologyStrategy") {
      _session.execute(
        s"CREATE KEYSPACE IF NOT EXISTS otoroshi WITH replication = {'class':'NetworkTopologyStrategy', $cassandraReplicationOptions};"
      )
    } else {
      _session.execute(
        s"CREATE KEYSPACE IF NOT EXISTS otoroshi WITH replication = {'class':'SimpleStrategy', 'replication_factor':$cassandraReplicationFactor};"
      )
    }
    _session.execute("USE otoroshi")
    _session.execute("CREATE TABLE IF NOT EXISTS otoroshi.values ( key text, type text, ttlv text, value text, lvalue list<text>, svalue set<text>, mvalue map<text, text>, PRIMARY KEY (key) );")
    _session.execute("CREATE TABLE IF NOT EXISTS otoroshi.counters ( key text, cvalue counter, PRIMARY KEY (key) );")
    _session.execute("CREATE TABLE IF NOT EXISTS otoroshi.expirations ( key text, value bigint, PRIMARY KEY (key) );")

    cancel.set(actorSystem.scheduler.schedule(1.second, 2.seconds) {
      val time = System.currentTimeMillis()
      executeAsync("SELECT key, value from otoroshi.expirations;").map { rs =>
        rs.asScala.foreach { row =>
          val key   = row.getString("key")
          val value = row.getLong("value")
          if (value < time) {
            executeAsync(s"DELETE FROM otoroshi.counters where key = '$key';")
            executeAsync(s"DELETE FROM otoroshi.expirations where key = '$key';")
          }
        }
      }
    })
    CassandraRedis.logger.info("Keyspace and table creation done !")
    // reporter.start(20, TimeUnit.SECONDS)
  }

  override def stop(): Unit = {
    Option(cancel.get()).foreach(_.cancel())
    reporter.stop()
    _session.close()
    cluster.close()
  }

  private val blockAsync = false
  private def executeAsync(query: String): Future[ResultSet] = {
    val readQuery = query.toLowerCase().trim.startsWith("select ")
    val timer = metrics.timer("cassandra.ops").time()
    val timerOp = metrics.timer(if (readQuery) "cassandra.reads" else "cassandra.writes").time()
    val rsf = _session.executeAsync(query)
    val promise = Promise[ResultSet]
    if (blockAsync) {
      promise.trySuccess(rsf.get())
      timer.close()
      timerOp.close()
    } else {
      rsf.addListener(
        new Runnable {
          override def run(): Unit =
            try {
              val rs = rsf.getUninterruptibly(1, TimeUnit.MILLISECONDS)
              promise.trySuccess(rs)
              timer.close()
              timerOp.close()
            } catch {
              case e: InvalidQueryException =>
                CassandraRedis.logger.error(s"""Cassandra query error: ${e.getMessage}. Query was: "$query"""")
                promise.tryFailure(e)
                metrics.counter("cassandra.errors").inc()
              case e: Throwable =>
                CassandraRedis.logger.error(s"""Cassandra error: ${e.getMessage}. Query was: "$query"""")
                promise.tryFailure(e)
                metrics.counter("cassandra.errors").inc()
            }
        },
        actorSystem.dispatcher
      )
    }
    promise.future
  }

  private def getAllKeys(): Future[Seq[String]] =
    for {
      values <- executeAsync("SELECT key from otoroshi.values;")
        .map(_.asScala.map(_.getString("key")).toSeq)
      counters <- executeAsync("SELECT key from otoroshi.counters;")
        .map(_.asScala.map(_.getString("key")).toSeq)
    } yield values ++ counters

  private def getValueAt(key: String): Future[Option[String]] =
    executeAsync(s"SELECT value from otoroshi.values where key = '$key';").flatMap { rs =>
      Try(rs.one().getString("value")).toOption.flatMap(o => Option(o)) match {
        case Some(v) => FastFuture.successful(Some(v))
        case None => executeAsync(s"SELECT cvalue from otoroshi.counters where key = '$key';").map { r =>
          Try(r.one().getLong("cvalue")).toOption.flatMap(o => Option(o)).map(_.toString)
        }
      }
    }

  private def getTypeAndValueAt(key: String): Future[Option[(String, String)]] =
    executeAsync(s"SELECT value, type from otoroshi.values where key = '$key';").flatMap { rs =>
      Try {
        val row = rs.one()
        val value = row.getString("value")
        val typ = row.getString("type")
        Option(typ).flatMap(t => Option(value).map(v => (t, v)))
      }.toOption.flatten  match {
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
      exp <- getExpirationAt(key)
      typAndValue <- getTypeAndValueAt(key)
    } yield (exp, typAndValue) match {
      case (e, Some((t, v))) => Some((t, e, v))
      case _ => None
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
    ((exSeconds, pxMilliseconds) match {
      case (Some(seconds), Some(_)) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') USING TTL $seconds;")
      case (Some(seconds), None) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') USING TTL $seconds;")
      case (None, Some(millis)) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string','${value.utf8String}') USING TTL ${millis / 1000};")
      case (None, None) => executeAsync(s"INSERT INTO otoroshi.values (key, type, value) values ('$key', 'string', '${value.utf8String}');")
    }).map(r => r.wasApplied())
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
    executeAsync(s"SELECT key FROM otoroshi.values WHERE key = '$key' LIMIT 1").map(rs => rs.asScala.nonEmpty).flatMap {
      case true => FastFuture.successful(true)
      case false => executeAsync(s"SELECT key FROM otoroshi.counters WHERE key = '$key' LIMIT 1")
        .map(rs => rs.asScala.nonEmpty)
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
    executeAsync(s"UPDATE otoroshi.values SET mvalue = mvalue - {${fields.map(v => s"'$v'").mkString(", ")}} WHERE key = '$key';").map(_ => fields.size)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = getMapAt(key)

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] =
    executeAsync(s"INSERT INTO otoroshi.values (key, type, mvalue) values ('$key', 'hash', {}) IF NOT EXISTS")
      .flatMap { _ =>
        executeAsync(
            s"UPDATE otoroshi.values SET mvalue = mvalue + {'$field' : '${value.utf8String}'} WHERE key = '$key';"
          )
          .map(_ => true)
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
        val list = values.map(_.utf8String).map(v => s"'$v'").mkString(",")
        executeAsync(s"UPDATE otoroshi.values SET lvalue = [ $list ] + lvalue  where key = '$key';")
          .map(_ => values.size)
      }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] =
    getListAt(key).map(_.slice(start.toInt, stop.toInt - start.toInt))

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] =
    getListAt(key).flatMap { list =>
      if (list.nonEmpty) {
        val listStr = list.slice(start.toInt, stop.toInt - start.toInt).map(a => s"'${a.utf8String}'").mkString(",")
        executeAsync(s"UPDATE otoroshi.values SET lvalue = [ $listStr ] where key = '$key';")
          .map(_ => true)
      } else {
        FastFuture.successful(true)
      }
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = {
    executeAsync(s"SELECT ttl(value) as ttl FROM otoroshi.values WHERE key = '$key' LIMIT 1").flatMap { r =>
      Try(r.one().getLong("ttl")).toOption.flatMap(o => Option(o)).map(_.toLong) match {
        case Some(ttl) => FastFuture.successful(Some(ttl))
        case None => getExpirationFromExpirationsTableAt(key).map {
          case v =>
            val ttlValue: Long = v - System.currentTimeMillis()
            Some(if (ttlValue < 0) -1L else ttlValue)
          case -1L => None
        }
      }
    }.map {
      case Some(o) => o
      case None    => -1L
    }
  }

  override def ttl(key: String): Future[Long] =
    pttl(key).map(t => scala.concurrent.duration.Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = pexpire(key, seconds * 1000)

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    // TODO: improve null assignement here here !!!
    val time = System.currentTimeMillis() + milliseconds
    for {
      a <- executeAsync(s"UPDATE otoroshi.values USING TTL ${milliseconds / 1000} SET ttlv = null where key = '$key';")
      b <- executeAsync(s"INSERT INTO otoroshi.expirations (key, value) values ('$key', $time);")
    } yield a.wasApplied() || b.wasApplied()
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    executeAsync(s"INSERT INTO otoroshi.values (key, type, svalue) values ('$key', 'set', {}) IF NOT EXISTS;")
      .flatMap { _ =>
        executeAsync(
          s"UPDATE otoroshi.values SET svalue = svalue + { ${members.map(v => s"'${v.utf8String}'").mkString(", ")} } where key = '$key';"
        ).map(_ => members.size)
    }
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] =
    getSetAt(key).map(_.contains(member))

  override def smembers(key: String): Future[Seq[ByteString]] = getSetAt(key).map(_.toSeq)

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    executeAsync(s"UPDATE otoroshi.values SET svalue = svalue - { ${members.map(v => s"'${v.utf8String}'").mkString(", ")} } WHERE key = '$key' IF EXISTS;")
      .map(_ => members.size)
  }

  override def scard(key: String): Future[Long] = {
    // executeAsync(s"SELECT size(svalue) as size FROM otoroshi.values WHERE key = '$key';").map(r => Try(r.one().getLong("size")).toOption.flatMap(o => Option(o)).getOrElse(0))
    smembers(key).map(_.size.toLong) // OUTCH !!!
  }

  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    executeAsync("SHOW VERSION").map(_ => Healthy).recover {
      case _ => Unreachable
    }
  }
}