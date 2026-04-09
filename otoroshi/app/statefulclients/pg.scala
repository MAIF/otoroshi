package otoroshi.statefulclients

import io.vertx.pgclient.{PgConnectOptions, PgPool}
import io.vertx.sqlclient.PoolOptions
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import java.util.concurrent.atomic.AtomicBoolean

object PgStatefulClientConfig {
  def apply(obj: JsObject) = new PgStatefulClientConfig(
    obj.select("uri").asString,
    obj.select("poolSize").asOpt[Int].getOrElse(10)
  )
}

class PgStatefulClientConfig(private val uri: String, private val poolSize: Int = 10) extends StatefulClientConfig[PgPool] {

  private val open = new AtomicBoolean(false)

  override def start(): PgPool = {
    val connectOptions = PgConnectOptions.fromUri(uri)
    val poolOptions = new PoolOptions().setMaxSize(poolSize)
    val pool = PgPool.pool(connectOptions, poolOptions)
    open.set(true)
    pool
  }

  override def stop(client: PgPool): Unit = {
    open.set(false)
    client.close()
  }

  override def isOpen(client: PgPool): Boolean = open.get()

  override def sameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case p: PgStatefulClientConfig => p.uri == uri && p.poolSize == poolSize
    case _ => false
  }
}
