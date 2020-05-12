package otoroshi.storage.stores

import akka.http.scaladsl.util.FastFuture
import env.Env
import otoroshi.models._
import play.api.libs.json._
import utils.JsonImplicits._

import scala.util.Success
import akka.util.ByteString
import otoroshi.storage.RedisLike
import org.joda.time.DateTime
import play.api.Logger
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}

class KvSimpleAdminDataStore(redisCli: RedisLike, _env: Env) extends SimpleAdminDataStore {

  lazy val logger = Logger("otoroshi-simple-admin-datastore")

  def key(id: String): String = s"${_env.storageRoot}:admins:$id"

  override def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[SimpleOtoroshiAdmin]] =
    redisCli.get(key(username)).map(_.map(v => Json.parse(v.utf8String)).flatMap { user =>
      SimpleOtoroshiAdmin.reads(user).asOpt
    })

  override def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[SimpleOtoroshiAdmin]] =
    redisCli
      .keys(key("*"))
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else redisCli.mget(keys: _*)
      )
      .map(seq => seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String)).flatMap { user =>
        SimpleOtoroshiAdmin.reads(user).asOpt
      })

  override def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.del(key(username))

  def deleteUsers(usernames: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisCli.del(usernames.map(v => key(v)): _*)
  }

  override def registerUser(user: SimpleOtoroshiAdmin)(
    implicit ec: ExecutionContext,
    env: Env
  ): Future[Boolean] = {
    redisCli.set(
      key(user.username),
      user.json.stringify
    )
  }

  override def hasAlreadyLoggedIn(username: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"${env.storageRoot}:users:alreadyloggedin", username)

  override def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.sadd(s"${env.storageRoot}:users:alreadyloggedin", email)
}
