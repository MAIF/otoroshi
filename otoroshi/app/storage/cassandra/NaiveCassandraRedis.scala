package storage.cassandra

import java.util.concurrent._
import java.util.regex.Pattern

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.datastax.driver.core.exceptions.InvalidQueryException
import com.datastax.driver.core.{ResultSet, ResultSetFuture, ThreadingOptions}
import com.datastax.shaded.netty.util.concurrent.DefaultThreadFactory
import com.google.common.util.concurrent.ThreadFactoryBuilder
import play.api.{Configuration, Logger}
import storage.{DataStoreHealth, Healthy, RedisLike, Unreachable}
import utils.SchedulerHelper

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

object Implicits {

  val blocking = false

  implicit class EnhancedResultSetFuture(val rsf: ResultSetFuture) extends AnyVal {
    def asFuture(implicit executor: Executor): Future[ResultSet] = {
      val promise = Promise[ResultSet]
      if (blocking) {
        promise.trySuccess(rsf.get())
      } else {
        rsf.addListener(
          new Runnable {
            override def run(): Unit =
              try {
                val rs = rsf.getUninterruptibly(10, TimeUnit.MILLISECONDS)
                promise.trySuccess(rs)
              } catch {
                case e: Throwable =>
                  CassandraRedis.logger.error("Cassandra error implicit", e)
                  promise.tryFailure(e)
              }
          },
          executor
        )
      }
      promise.future
    }
  }

  implicit class ConditionalEffect[T](val any: T) extends AnyVal {
    def withCondition(p: => Boolean)(f: T => T): T = p match {
      case true  => f(any)
      case false => any
    }
  }
}

class CassandraThreadingOptions(configuration: Configuration) extends ThreadingOptions {

  val reaperExecutorSize = configuration.getOptional[Int]("app.cassandra.threading.reaperExecutorSize").getOrElse(1)
  val scheduledTasksExecutorSize =
    configuration.getOptional[Int]("app.cassandra.threading.scheduledTasksExecutorSize").getOrElse(1)
  val reconnectionExecutorSize =
    configuration.getOptional[Int]("app.cassandra.threading.reconnectionExecutorSize").getOrElse(2)
  val blockingExecutorSize = configuration.getOptional[Int]("app.cassandra.threading.blockingExecutorSize").getOrElse(2)
  val nonBlockingExecutorSize = configuration
    .getOptional[Int]("app.cassandra.threading.nonBlockingExecutorSize")
    .getOrElse(Runtime.getRuntime.availableProcessors * 2 + 1)
  val threadKeepAliveSeconds =
    configuration.getOptional[Int]("app.cassandra.threading.threadKeepAliveSeconds").getOrElse(30)
  val executorName = configuration.getOptional[String]("app.cassandra.threading.executorName").getOrElse("worker")
  val blockingExecutorName =
    configuration.getOptional[String]("app.cassandra.threading.blockingExecutorName").getOrElse("blocking-task-worker")
  val reconnectionExecutorName =
    configuration.getOptional[String]("app.cassandra.threading.blockingExecutorName").getOrElse("reconnection")
  val scheduledTasksExecutorName = configuration
    .getOptional[String]("app.cassandra.threading.scheduledTasksExecutorName")
    .getOrElse("scheduled-task-worker")
  val reaperExecutorName =
    configuration.getOptional[String]("app.cassandra.threading.reaperExecutorName").getOrElse("connection-reaper")

  override def createThreadFactory(clusterName: String, executorName: String): ThreadFactory = {
    (new ThreadFactoryBuilder)
      .setNameFormat(clusterName + "-" + executorName + "-%d")
      .setThreadFactory(new DefaultThreadFactory("ignored name"))
      .build
  }

  override def createExecutor(clusterName: String): ExecutorService = {
    val executor = new ThreadPoolExecutor(
      nonBlockingExecutorSize,
      nonBlockingExecutorSize,
      threadKeepAliveSeconds,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable](),
      this.createThreadFactory(clusterName, executorName)
    )
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  override def createBlockingExecutor(clusterName: String): ExecutorService = {
    val executor = new ThreadPoolExecutor(
      blockingExecutorSize,
      blockingExecutorSize,
      threadKeepAliveSeconds,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable](),
      this.createThreadFactory(clusterName, blockingExecutorName)
    )
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  override def createReconnectionExecutor(clusterName: String): ScheduledExecutorService = {
    new ScheduledThreadPoolExecutor(reconnectionExecutorSize,
                                    this.createThreadFactory(clusterName, reconnectionExecutorName))
  }

  override def createScheduledTasksExecutor(clusterName: String): ScheduledExecutorService = {
    new ScheduledThreadPoolExecutor(scheduledTasksExecutorSize,
                                    this.createThreadFactory(clusterName, scheduledTasksExecutorName))
  }

  override def createReaperExecutor(clusterName: String): ScheduledExecutorService = {
    new ScheduledThreadPoolExecutor(reaperExecutorSize, this.createThreadFactory(clusterName, reaperExecutorName))
  }
}

trait RawGetRedis {
  def rawGet(key: String): Future[Option[(String, Long, Any)]]
}

/**
 * Really dumb and naive support for cassandra, not production ready I guess
 *
 * docker run \
 *   -p 7000:7000 \
 *   -p 7001:7001 \
 *   -p 7199:7199 \
 *   -p 9042:9042 \
 *   -p 9160:9160 \
 *   -p 9142:9142 \
 *   -e CASSANDRA_BROADCAST_ADDRESS=127.0.0.1 \
 *   cassandra:3.11.4
 * docker run -it cassandra:3.11.4 cqlsh 172.21.198.38 9042 -k otoroshi
 */
class CassandraRedisNaive(actorSystem: ActorSystem, configuration: Configuration) extends RedisLike with RawGetRedis {

  import Implicits._
  import actorSystem.dispatcher
  import com.datastax.driver.core._

  import collection.JavaConverters._
  import scala.concurrent.duration._

  private val patterns = new ConcurrentHashMap[String, Pattern]()

  val cassandraContactPoints: Seq[String] = configuration
    .getOptional[String]("app.cassandra.hosts")
    .map(_.split(",").toSeq)
    .orElse(
      configuration.getOptional[String]("app.cassandra.host").map(e => Seq(e))
    )
    .getOrElse(Seq("127.0.0.1"))
  val cassandraReplicationStrategy: String =
    configuration.getOptional[String]("app.cassandra.replicationStrategy").getOrElse("SimpleStrategy")
  val cassandraReplicationOptions: String =
    configuration.getOptional[String]("app.cassandra.replicationOptions").getOrElse("'dc0': 1")
  val cassandraReplicationFactor: Int =
    configuration.getOptional[Int]("app.cassandra.replicationFactor").getOrElse(1)
  val cassandraPort: Int            = configuration.getOptional[Int]("app.cassandra.port").getOrElse(9042)
  val maybeUsername: Option[String] = configuration.getOptional[String]("app.cassandra.username")
  val maybePassword: Option[String] = configuration.getOptional[String]("app.cassandra.password")

  val poolingExecutor: Executor = configuration
    .getOptional[Int]("app.cassandra.pooling.initializationExecutorThreads")
    .map(n => Executors.newFixedThreadPool(n))
    .getOrElse(actorSystem.dispatcher)
  val poolingOptions = new PoolingOptions()
    .setMaxQueueSize(configuration.getOptional[Int]("app.cassandra.pooling.maxQueueSize").getOrElse(2048))
    .setCoreConnectionsPerHost(
      HostDistance.LOCAL,
      configuration.getOptional[Int]("app.cassandra.pooling.coreConnectionsPerLocalHost").getOrElse(1)
    )
    .setMaxConnectionsPerHost(
      HostDistance.LOCAL,
      configuration.getOptional[Int]("app.cassandra.pooling.coreConnectionsPerRemoteHost").getOrElse(1)
    )
    .setCoreConnectionsPerHost(
      HostDistance.REMOTE,
      configuration.getOptional[Int]("app.cassandra.pooling.maxConnectionsPerLocalHost").getOrElse(1)
    )
    .setMaxConnectionsPerHost(
      HostDistance.REMOTE,
      configuration.getOptional[Int]("app.cassandra.pooling.maxConnectionsPerRemoteHost").getOrElse(1)
    )
    .setMaxRequestsPerConnection(
      HostDistance.LOCAL,
      configuration.getOptional[Int]("app.cassandra.pooling.maxRequestsPerLocalConnection").getOrElse(32768)
    )
    .setMaxRequestsPerConnection(
      HostDistance.REMOTE,
      configuration.getOptional[Int]("app.cassandra.pooling.maxRequestsPerRemoteConnection").getOrElse(2048)
    )
    .setNewConnectionThreshold(
      HostDistance.LOCAL,
      configuration.getOptional[Int]("app.cassandra.pooling.newLocalConnectionThreshold").getOrElse(30000)
    )
    .setNewConnectionThreshold(
      HostDistance.REMOTE,
      configuration.getOptional[Int]("app.cassandra.pooling.newRemoteConnectionThreshold").getOrElse(400)
    )
    .setPoolTimeoutMillis(configuration.getOptional[Int]("app.cassandra.pooling.poolTimeoutMillis").getOrElse(0))
    .setInitializationExecutor(poolingExecutor)
    .setHeartbeatIntervalSeconds(
      configuration.getOptional[Int]("app.cassandra.pooling.heartbeatIntervalSeconds").getOrElse(30)
    )
    .setIdleTimeoutSeconds(configuration.getOptional[Int]("app.cassandra.pooling.idleTimeoutSeconds").getOrElse(120))

  val clusterBuilder = Cluster
    .builder()
    .withClusterName(configuration.getOptional[String]("app.cassandra.clusterName").getOrElse("otoroshi-cluster"))
    .addContactPoints(cassandraContactPoints: _*)
    .withPort(cassandraPort)
    .withProtocolVersion(
      configuration
        .getOptional[String]("app.cassandra.protocol")
        .map(v => ProtocolVersion.valueOf(v))
        .getOrElse(ProtocolVersion.V4)
    )
    .withMaxSchemaAgreementWaitSeconds(
      configuration.getOptional[Int]("app.cassandra.maxSchemaAgreementWaitSeconds").getOrElse(10)
    )
    .withCondition(!configuration.getOptional[Boolean]("app.cassandra.compactEnabled").getOrElse(false))(
      _.withNoCompact()
    )
    .withCondition(!configuration.getOptional[Boolean]("app.cassandra.metricsEnabled").getOrElse(false))(
      _.withoutMetrics()
    )
    .withCondition(!configuration.getOptional[Boolean]("app.cassandra.jmxReportingEnabled").getOrElse(false))(
      _.withoutJMXReporting()
    )
    .withCondition(configuration.getOptional[Boolean]("app.cassandra.sslEnabled").getOrElse(false))(_.withSSL())
    .withCompression(
      ProtocolOptions.Compression
        .valueOf(configuration.getOptional[String]("app.cassandra.compression").getOrElse("NONE"))
    )
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

  val cluster: Cluster = (for {
    username <- maybeUsername
    password <- maybePassword
  } yield {
    clusterBuilder.withCredentials(username, password)
  }).getOrElse(clusterBuilder).build()

  val session = cluster.connect()

  // aweful stuff here !
  private val cancel = actorSystem.scheduler.scheduleAtFixedRate(0.millis, 1.second)(SchedulerHelper.runnable {
    val time = System.currentTimeMillis()
    session.executeAsync("SELECT key, value from otoroshi.expirations;").asFuture.map { rs =>
      rs.asScala.foreach { row =>
        val key   = row.getString("key")
        val value = row.getLong("value")
        if (value < time) {
          session.executeAsync(s"DELETE FROM otoroshi.values where key = '$key';")
          session.executeAsync(s"DELETE FROM otoroshi.sets where key = '$key';")
          session.executeAsync(s"DELETE FROM otoroshi.lists where key = '$key';")
          session.executeAsync(s"DELETE FROM otoroshi.hashs where key = '$key';")
          session.executeAsync(s"DELETE FROM otoroshi.counters where key = '$key';")
          session.executeAsync(s"DELETE FROM otoroshi.expirations where key = '$key';")
        }
      }
    }
  })

  override def start(): Unit = {
    CassandraRedis.logger.info("Creating database keyspace and tables if not exists ...")
    if (cassandraReplicationStrategy == "NetworkTopologyStrategy") {
      session.execute(
        s"CREATE KEYSPACE IF NOT EXISTS otoroshi WITH replication = {'class':'NetworkTopologyStrategy', $cassandraReplicationOptions};"
      )
    } else {
      session.execute(
        s"CREATE KEYSPACE IF NOT EXISTS otoroshi WITH replication = {'class':'SimpleStrategy', 'replication_factor':$cassandraReplicationFactor};"
      )
    }
    session.execute("USE otoroshi")
    session.execute("CREATE TABLE IF NOT EXISTS otoroshi.values ( key text, value text, PRIMARY KEY (key) );")
    session.execute("CREATE TABLE IF NOT EXISTS otoroshi.lists ( key text, value list<text>, PRIMARY KEY (key) );")
    session.execute("CREATE TABLE IF NOT EXISTS otoroshi.sets ( key text, value set<text>, PRIMARY KEY (key) );")
    session.execute("CREATE TABLE IF NOT EXISTS otoroshi.hashs ( key text, value map<text, text>, PRIMARY KEY (key) );")
    session.execute("CREATE TABLE IF NOT EXISTS otoroshi.counters ( key text, value counter, PRIMARY KEY (key) );")
    session.execute("CREATE TABLE IF NOT EXISTS otoroshi.expirations ( key text, value bigint, PRIMARY KEY (key) );")
    CassandraRedis.logger.info("Keyspace and table creation done !")
  }

  override def stop(): Unit = {
    cancel.cancel()
    session.close()
    cluster.close()
  }

  private def getAllKeys(): Future[Seq[String]] =
    for {
      values <- session
                 .executeAsync("SELECT key from otoroshi.values;")
                 .asFuture
                 .map(_.asScala.map(_.getString("key")).toSeq)
      lists <- session
                .executeAsync("SELECT key from otoroshi.lists;")
                .asFuture
                .map(_.asScala.map(_.getString("key")).toSeq)
      sets <- session
               .executeAsync("SELECT key from otoroshi.sets;")
               .asFuture
               .map(_.asScala.map(_.getString("key")).toSeq)
      hashs <- session
                .executeAsync("SELECT key from otoroshi.hashs;")
                .asFuture
                .map(_.asScala.map(_.getString("key")).toSeq)
      counters <- session
                   .executeAsync("SELECT key from otoroshi.counters;")
                   .asFuture
                   .map(_.asScala.map(_.getString("key")).toSeq)
    } yield values ++ lists ++ sets ++ hashs ++ counters

  private def getValueAt(key: String): Future[Option[String]] =
    session.executeAsync(s"SELECT value from otoroshi.values where key = '$key';").asFuture.flatMap { rs =>
      Try(rs.one().getString("value")).toOption.flatMap(o => Option(o)) match {
        case Some(v) => FastFuture.successful(Some(v))
        case None =>
          session.executeAsync(s"SELECT value from otoroshi.counters where key = '$key';").asFuture.map { r =>
            Try(r.one().getLong("value")).toOption.flatMap(o => Option(o)).map(_.toString)
          }
      }
    }

  private def getCounterAt(key: String): Future[Long] =
    session.executeAsync(s"SELECT value from otoroshi.counters where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getLong("value")).toOption.flatMap(o => Option(o)).getOrElse(0L)
    }

  private def getExpirationAt(key: String): Future[Long] =
    session.executeAsync(s"SELECT value from otoroshi.expirations where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getLong("value")).toOption.flatMap(o => Option(o)).getOrElse(0L)
    }

  private def getListAt(key: String): Future[Seq[ByteString]] =
    session.executeAsync(s"SELECT value from otoroshi.lists where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getList("value", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.map(ByteString.apply).toSeq)
        .getOrElse(Seq.empty[ByteString])
    }

  private def getSetAt(key: String): Future[Set[ByteString]] =
    session.executeAsync(s"SELECT value from otoroshi.sets where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getSet("value", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toSet.map((e: String) => ByteString(e)))
        .getOrElse(Set.empty[ByteString])
    }

  private def getMapAt(key: String): Future[Map[String, ByteString]] =
    session.executeAsync(s"SELECT value from otoroshi.hashs where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getMap("value", classOf[String], classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toMap.mapValues(ByteString.apply))
        .getOrElse(Map.empty[String, ByteString])
    }

  private def getCounterOptAt(key: String): Future[Option[Long]] =
    session.executeAsync(s"SELECT value from otoroshi.counters where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getLong("value")).toOption.flatMap(o => Option(o))
    }

  private def getListOptAt(key: String): Future[Option[Seq[ByteString]]] =
    session.executeAsync(s"SELECT value from otoroshi.lists where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getList("value", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.map(ByteString.apply).toSeq)
    }

  private def getSetOptAt(key: String): Future[Option[Set[ByteString]]] =
    session.executeAsync(s"SELECT value from otoroshi.sets where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getSet("value", classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toSet.map((e: String) => ByteString(e)))
    }

  private def getMapOptAt(key: String): Future[Option[Map[String, ByteString]]] =
    session.executeAsync(s"SELECT value from otoroshi.hashs where key = '$key';").asFuture.map { rs =>
      Try(rs.one().getMap("value", classOf[String], classOf[String])).toOption
        .flatMap(o => Option(o))
        .map(_.asScala.toMap.mapValues(ByteString.apply))
    }

  override def flushall(): Future[Boolean] =
    for {
      _ <- session.executeAsync("TRUNCATE otoroshi.values").asFuture
      _ <- session.executeAsync("TRUNCATE otoroshi.lists").asFuture
      _ <- session.executeAsync("TRUNCATE otoroshi.sets").asFuture
      _ <- session.executeAsync("TRUNCATE otoroshi.hashs").asFuture
      _ <- session.executeAsync("TRUNCATE otoroshi.counters").asFuture
      _ <- session.executeAsync("TRUNCATE otoroshi.expirations").asFuture
    } yield true

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def rawGet(key: String): Future[Option[(String, Long, Any)]] = {
    getExpirationAt(key).flatMap { ttl =>
      getValueAt(key).flatMap {
        case Some(value) => FastFuture.successful(Some(("string", ttl, value)))
        case None =>
          getCounterOptAt(key).flatMap {
            case Some(value) => FastFuture.successful(Some(("string", ttl, value)))
            case None =>
              getListOptAt(key).flatMap {
                case Some(value) => FastFuture.successful(Some(("list", ttl, value)))
                case None =>
                  getSetOptAt(key).flatMap {
                    case Some(value) => FastFuture.successful(Some(("set", ttl, value)))
                    case None =>
                      getMapOptAt(key).map {
                        case Some(value) => Some(("hash", ttl, value))
                        case None        => None
                      }
                  }
              }
          }
      }
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
                     pxMilliseconds: Option[Long] = None): Future[Boolean] =
    (for {
      _ <- session
            .executeAsync(
              s"INSERT INTO otoroshi.values (key, value) values ('$key', '${value.utf8String}') IF NOT EXISTS;"
            )
            .asFuture
      _ <- session
            .executeAsync(s"UPDATE otoroshi.values SET value = '${value.utf8String}' where key = '$key' IF EXISTS;")
            .asFuture
    } yield ()) flatMap { _ =>
      if (exSeconds.isDefined) {
        expire(key, exSeconds.get.toInt)
      } else if (pxMilliseconds.isDefined) {
        pexpire(key, pxMilliseconds.get)
      } else {
        FastFuture.successful(true)
      }
    }

  override def del(keys: String*): Future[Long] =
    Future
      .sequence(
        keys.map { k =>
          for {
            _ <- session.executeAsync(s"DELETE FROM otoroshi.values where key = '$k' IF EXISTS;").asFuture
            _ <- session.executeAsync(s"DELETE FROM otoroshi.lists where key = '$k' IF EXISTS;").asFuture
            _ <- session.executeAsync(s"DELETE FROM otoroshi.sets where key = '$k' IF EXISTS;").asFuture
            _ <- session.executeAsync(s"DELETE FROM otoroshi.hashs where key = '$k' IF EXISTS;").asFuture
            _ <- session.executeAsync(s"DELETE FROM otoroshi.counters where key = '$k' IF EXISTS;").asFuture
          } yield 1L
        }
      )
      .map(_.foldLeft(0L)(_ + _))

  override def incr(key: String): Future[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): Future[Long] =
    session
      .executeAsync(s"UPDATE otoroshi.counters SET value = value + $increment WHERE key = '$key';")
      .asFuture
      .flatMap { rs =>
        getCounterAt(key)
      }

  override def exists(key: String): Future[Boolean] =
    for {
      a <- session
            .executeAsync(s"SELECT key FROM otoroshi.values WHERE key = '$key' LIMIT 1")
            .asFuture
            .map(rs => rs.asScala.nonEmpty)
      b <- session
            .executeAsync(s"SELECT key FROM otoroshi.lists WHERE key = '$key' LIMIT 1")
            .asFuture
            .map(rs => rs.asScala.nonEmpty)
      c <- session
            .executeAsync(s"SELECT key FROM otoroshi.sets WHERE key = '$key' LIMIT 1")
            .asFuture
            .map(rs => rs.asScala.nonEmpty)
      d <- session
            .executeAsync(s"SELECT key FROM otoroshi.counters WHERE key = '$key' LIMIT 1")
            .asFuture
            .map(rs => rs.asScala.nonEmpty)
      e <- session
            .executeAsync(s"SELECT key FROM otoroshi.hashs WHERE key = '$key' LIMIT 1")
            .asFuture
            .map(rs => rs.asScala.nonEmpty)
    } yield a && b && c && d && e

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    Future.sequence(keys.map(k => get(k)))

  override def keys(pattern: String): Future[Seq[String]] = {
    val pat = patterns.computeIfAbsent(pattern, _ => Pattern.compile(pattern.replaceAll("\\*", ".*")))
    getAllKeys().map(_.filter { k =>
      pat.matcher(k).find
    })
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] =
    Future
      .sequence(
        fields.map(
          field =>
            session
              .executeAsync(s"DELETE value ['$field'] FROM otoroshi.hashs WHERE key = '$key' IF EXISTS;")
              .asFuture
              .map(_ => 1L)
        )
      )
      .map(_.foldLeft(0L)(_ + _))

  override def hgetall(key: String): Future[Map[String, ByteString]] = getMapAt(key)

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] =
    session
      .executeAsync(s"INSERT INTO otoroshi.hashs (key, value) values ('$key', {}) IF NOT EXISTS")
      .asFuture
      .flatMap { _ =>
        session
          .executeAsync(
            s"UPDATE otoroshi.hashs SET value = value + {'$field' : '${value.utf8String}'} WHERE key = '$key';"
          )
          .asFuture
          .map(_ => true)
      }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def llen(key: String): Future[Long] =
    getListAt(key).map(_.size)

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] =
    session
      .executeAsync(s"INSERT INTO otoroshi.lists (key, value) values ('$key', [ ]) IF NOT EXISTS;")
      .asFuture
      .flatMap { _ =>
        val list = values.map(_.utf8String).map(v => s"'$v'").mkString(",")
        session
          .executeAsync(s"UPDATE otoroshi.lists SET value = [ $list ] + value  where key = '$key';")
          .asFuture
          .map(_ => values.size)
      }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] =
    getListAt(key).map(_.slice(start.toInt, stop.toInt - start.toInt))

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] =
    getListAt(key).flatMap { list =>
      if (list.nonEmpty) {
        val listStr = list.slice(start.toInt, stop.toInt - start.toInt).map(a => s"'${a.utf8String}'").mkString(",")
        session
          .executeAsync(s"UPDATE otoroshi.lists SET value = [ $listStr ] where key = '$key';")
          .asFuture
          .map(_ => true)
      } else {
        FastFuture.successful(true)
      }
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] =
    getValueAt(key).map {
      case Some(v) =>
        val ttlValue = v.toLong - System.currentTimeMillis()
        if (ttlValue < 0) -1L else ttlValue
      case None => -1L
    }

  override def ttl(key: String): Future[Long] =
    pttl(key).map(t => scala.concurrent.duration.Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = pexpire(key, seconds * 1000)

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    val time = System.currentTimeMillis() + milliseconds
    for {
      a <- session
            .executeAsync(s"INSERT INTO otoroshi.expirations (key, value) values ('$key', $time) IF NOT EXISTS;")
            .asFuture
            .map(_ => true)
      b <- session
            .executeAsync(s"UPDATE otoroshi.expirations SET value = $time where key = '$key';")
            .asFuture
            .map(_ => true)
    } yield a && b
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] =
    session
      .executeAsync(s"INSERT INTO otoroshi.sets (key, value) values ('$key', {}) IF NOT EXISTS;")
      .asFuture
      .flatMap { _ =>
        Future
          .sequence(
            members.map { member =>
              session
                .executeAsync(
                  s"UPDATE otoroshi.sets SET value = value + { '${member.utf8String}' } where key = '$key';"
                )
                .asFuture
                .map(_ => 1L)
            }
          )
          .map(_.foldLeft(0L)(_ + _))
      }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] =
    getSetAt(key).map(_.contains(member))

  override def smembers(key: String): Future[Seq[ByteString]] = getSetAt(key).map(_.toSeq)

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] =
    Future
      .sequence(
        members.map(
          members =>
            session
              .executeAsync(s"DELETE value ['$members'] FROM otoroshi.sets WHERE key = '$key' IF EXISTS;")
              .asFuture
              .map(_ => 1L)
        )
      )
      .map(_.foldLeft(0L)(_ + _))

  override def scard(key: String): Future[Long] =
    smembers(key).map(_.size.toLong) // OUTCH !!!

  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    session.executeAsync("SHOW VERSION").asFuture.map(_ => Healthy).recover {
      case _ => Unreachable
    }
  }
}
