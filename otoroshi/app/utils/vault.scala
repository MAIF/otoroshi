package otoroshi.utils

import akka.http.scaladsl.model.Uri
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsNull, _}

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

trait Vault {
  def get(path: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[Option[String]]
}

class EnvVault(env: Env) extends Vault {
  override def get(path: String, options: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[Option[String]] = {
    val parts = path.split("/").toSeq.map(_.trim).filterNot(_.isEmpty)
    if (parts.isEmpty) {
      None.vfuture
    } else if (parts.size == 1) {
      val name = parts.head
      sys.env.get(name).orElse(sys.env.get(name.toUpperCase())).vfuture
    } else {
      val name = parts.head
      val pointer = parts.tail.mkString("/", "/", "")
      sys.env.get(name).orElse(sys.env.get(name.toUpperCase())).filter(_.trim.startsWith("{")).flatMap { jsonraw =>
        val obj = Json.parse(jsonraw).asOpt[JsObject].getOrElse(Json.obj())
        obj.atPointer(pointer).asOpt[JsValue] match {
          case Some(JsString(value)) => value.some
          case Some(JsNumber(value)) => value.toString().some
          case Some(JsBoolean(value)) => value.toString.some
          case Some(o @ JsObject(value)) => o.stringify.some
          case Some(arr @ JsArray(value)) => arr.stringify.some
          case Some(JsNull) => "null".some
          case _ => None
        }
      }.vfuture
    }
  }
}

class Vaults(env: Env) {

  private val cache = Scaffeine().expireAfterWrite(5.minutes).maximumSize(1000).build[String, String]()

  private val expressionReplacer = ReplaceAllWith("\\$\\{vault://([^}]*)\\}")

  private val vaults = new TrieMap[String, Vault]()

  private val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  vaults.put("env", new EnvVault(env))

  def fillSecrets(source: String): String = {
    expressionReplacer.replaceOn(source) { expr =>
      val uri = Uri(expr)
      val name = uri.authority.host.toString()
      val path = uri.path.toString()
      val options = uri.query().toMap
      println(name, path, options)
      cache.getIfPresent(expr) match {
        case Some(res) => res
        case None => {
          vaults.get(name) match {
            case None => "vault-not-found"
            case Some(vault) => {
              // TODO: populate a cache that will be periodically updated !
              Await.result(vault.get(path, options)(env, ec), 1.minute) match {
                case None => "secret-not-found"
                case Some(response) => {
                  cache.put(expr, response)
                  response
                }
              }
            }
          }
        }
      }
    }
  }
}
