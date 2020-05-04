package models

import env.Env
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

trait SimpleAdminDataStore {
  def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]]
  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]]
  def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def registerUser(username: String, password: String, label: String, authorizedGroup: Option[String])(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Boolean]
  def registerUser(user: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
}
