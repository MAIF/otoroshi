package otoroshi.controllers

import controllers.AssetsBuilder
import otoroshi.env.Env
import play.api.mvc.*

class SwaggerController(cc: ControllerComponents, assetsBuilder: AssetsBuilder)(using env: Env)
    extends AbstractController(cc) {

  def openapi: Action[AnyContent] = assetsBuilder.at("openapi.json")

  def openapiUi: Action[AnyContent] =
    Action { (req: Request[AnyContent]) =>
      Ok(
        otoroshi.views.html.oto.openapiFrame(
          s"${env.exposedRootScheme}://${env.backOfficeHost}${env.privateAppsPort}/apis/openapi.json"
        )
      )
    }
}
