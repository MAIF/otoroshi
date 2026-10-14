package plugins

import functional.PluginsTestSpecBase
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.actions.{ApiAction, BackOfficeActionAuth}
import otoroshi.env.Env
import otoroshi.next.extensions.*
import otoroshi.utils.syntax.implicits.*
import play.api.mvc.*
import play.api.test.FakeRequest

// the routers of the admin extensions are tried in turn, overrides first. an overrides router used to take every
// request as soon as an extension declared one override: a request that matched none of them never reached the
// regular extension routes
class AdminExtensionsRoutingTests(parent: PluginsTestSpecBase) {
  import parent.*

  class RoutingTestExtension(val env: Env) extends AdminExtension {
    override def id: AdminExtensionId        = AdminExtensionId("test.extensions.routing")
    override def enabled: Boolean            = true
    override def name: String                = "routing"
    override def description: Option[String] = None

    override def backofficePublicOverridesRoutes(): Seq[AdminExtensionBackofficePublicRoute] =
      Seq(AdminExtensionBackofficePublicRoute("GET", "/routing-test/overridden", false, (_, _, _) => Results.Ok.vfuture))
    override def backofficePublicRoutes(): Seq[AdminExtensionBackofficePublicRoute]          =
      Seq(AdminExtensionBackofficePublicRoute("GET", "/extensions/pub/routing-test", false, (_, _, _) => Results.Ok.vfuture))
    override def adminApiOverridesRoutes(): Seq[AdminExtensionAdminApiRoute]                 =
      Seq(AdminExtensionAdminApiRoute("GET", "/api/routing-test/overridden", false, (_, _, _, _) => Results.Ok.vfuture))
    override def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute]                          =
      Seq(AdminExtensionAdminApiRoute("GET", "/api/extensions/routing-test", false, (_, _, _, _) => Results.Ok.vfuture))
  }

  val extensions       = new AdminExtensions(env, Seq(new RoutingTestExtension(env)))
  val ignoreBody       = BodyParsers.utils.ignore[AnyContent](AnyContentAsEmpty)
  val actionBuilder    = DefaultActionBuilder(ignoreBody)(using env.otoroshiExecutionContext)
  val sourceBodyParser = BodyParsers.utils.ignore[Source[ByteString, ?]](Source.empty[ByteString])
  val backOfficeAction = new BackOfficeActionAuth(ignoreBody)(using env)
  val apiAction        = new ApiAction(ignoreBody)(using env)

  def backoffice(path: String): Boolean =
    extensions.handleBackofficeCall(FakeRequest("GET", path), actionBuilder, backOfficeAction, sourceBodyParser)(None).isDefined

  def adminApi(path: String): Boolean =
    extensions.handleAdminApiCall(FakeRequest("GET", path), actionBuilder, apiAction, sourceBodyParser)(None).isDefined

  withClue("backoffice override ") { backoffice("/routing-test/overridden") mustBe true }
  withClue("backoffice public route next to an override ") { backoffice("/extensions/pub/routing-test") mustBe true }
  withClue("backoffice unknown path ") { backoffice("/routing-test/nothing") mustBe false }
  withClue("admin api override ") { adminApi("/api/routing-test/overridden") mustBe true }
  withClue("admin api route next to an override ") { adminApi("/api/extensions/routing-test") mustBe true }
  withClue("admin api unknown path ") { adminApi("/api/routing-test/nothing") mustBe false }
}
