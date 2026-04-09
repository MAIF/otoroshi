package otoroshi.statefulclients

import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.{Pool, PoolOptions}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import java.util.concurrent.atomic.AtomicBoolean

object PgStatefulClientConfig {
  def apply(obj: JsObject) = new PgStatefulClientConfig(
    obj.select("uri").asString,
    obj.select("poolSize").asOpt[Int].getOrElse(10)
  )
}

case class PgStatefulClientConfig(uri: String, poolSize: Int = 10) extends StatefulClientConfig[Pool] {

  private val open = new AtomicBoolean(false)

  override def start(env: otoroshi.env.Env): Pool = {
    val connectOptions = PgConnectOptions.fromUri(uri)
    val poolOptions = new PoolOptions().setMaxSize(poolSize)
    val pool = Pool.pool(connectOptions, poolOptions)
    open.set(true)
    pool
  }

  override def stop(client: Pool): Unit = {
    open.set(false)
    client.close()
  }

  override def isOpen(client: Pool): Boolean = open.get()

  override def sameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case p: PgStatefulClientConfig => p.uri == uri && p.poolSize == poolSize
    case _ => false
  }
}
