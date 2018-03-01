package models

import com.yubico.u2f.data.DeviceRegistration
import env.Env
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

trait U2FAdminDataStore {
  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]]
  def deleteUser(username: String, id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def getRequest(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[String]]
  def addRequest(id: String, regData: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def deleteRequest(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def registerUser(username: String, password: String, label: String, reg: DeviceRegistration)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Boolean]
  def getUserRegistration(username: String)(implicit ec: ExecutionContext,
                                            env: Env): Future[Seq[(DeviceRegistration, JsValue)]]
}

trait SimpleAdminDataStore {
  def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]]
  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]]
  def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def registerUser(username: String, password: String, label: String, authorizedGroup: Option[String])(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Boolean]
  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
}
