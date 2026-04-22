package otoroshi.controllers

import controllers.AssetsBuilder
import otoroshi.env.Env
import play.api.mvc._

class SwaggerController(cc: ControllerComponents, assetsBuilder: AssetsBuilder)(implicit env: Env)
    extends AbstractController(cc) {

  def openapi = assetsBuilder.at("openapi.json")

  def openapiUi =
    Action { req =>
      Ok(
        otoroshi.views.html.oto.openapiFrame(
          s"${env.exposedRootScheme}://${env.backOfficeHost}${env.privateAppsPort}/apis/openapi.json"
        )
      )
    }
}
