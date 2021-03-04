package functional

import com.typesafe.config.ConfigFactory
import otoroshi.utils.workflow.{WorkFlow, WorkFlowRequest, WorkFlowSpec}
import play.api.Configuration
import play.api.libs.json.Json

class WorkFlowTestSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  implicit val mat = otoroshiComponents.materializer
  implicit val env = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
           |{
           |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  s"workflows" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
    }

    "work " in {
      val workflow = WorkFlow(
        WorkFlowSpec.inline(
          Json.obj(
            "name"        -> "test-workflow",
            "description" -> "",
            "tasks"       -> Json.arr(
              Json.obj(
                "name"      -> "call-dns",
                "type"      -> "http",
                "request"   -> Json.obj(
                  "method"  -> "GET",
                  "url"     -> "http://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT}/apis/operator.openshift.io/v1/dnses/default",
                  "headers" -> Json.obj(
                    "accept"        -> "application/json",
                    "authorization" -> "Bearer ${file:///var/run/secrets/kubernetes.io/serviceaccount/token}"
                  ),
                  "tls"     -> Json.obj(
                    "mtls"     -> true,
                    "trustAll" -> true
                  )
                ),
                "success"   -> Json.obj(
                  "statuses" -> Json.arr(200)
                )
              ),
              Json.obj(
                "name"      -> "call-service",
                "type"      -> "http",
                "request"   -> Json.obj(
                  "method"  -> "GET",
                  "url"     -> "http://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT}/apis/v1/services/otoroshi-dns",
                  "headers" -> Json.obj(
                    "accept"        -> "application/json",
                    "authorization" -> "Bearer ${file:///var/run/secrets/kubernetes.io/serviceaccount/token}"
                  ),
                  "tls"     -> Json.obj(
                    "mtls"     -> true,
                    "trustAll" -> true
                  )
                ),
                "success"   -> Json.obj(
                  "statuses" -> Json.arr(200)
                )
              ),
              Json.obj(
                "name"      -> "call-update",
                "type"      -> "http",
                "predicate" -> Json.obj(
                  "operator" -> "not-equals",
                  "left"     -> Json.obj(
                    "$path"       -> "$.responses.call-dns.body.spec.servers[?(@.name == 'otoroshi-dns')].forwardPlugin.upstreams[0]",
                    "$resultPath" -> "$.[0]"
                  ),
                  "right"    -> Json.obj(
                    "$path"   -> "$.responses.call-service.body.spec.clusterIP",
                    "$append" -> ":5353"
                  )
                ),
                "request"   -> Json.obj(
                  "method"  -> "PATCH",
                  "url"     -> "http://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT}/apis/operator.openshift.io/v1/dnses/default",
                  "headers" -> Json.obj(
                    "accept"        -> "application/json",
                    "content-type"  -> "application/json",
                    "authorization" -> "Bearer ${file:///var/run/secrets/kubernetes.io/serviceaccount/token}"
                  ),
                  "tls"     -> Json.obj(
                    "mtls"     -> true,
                    "trustAll" -> true
                  ),
                  "body"    -> Json.arr(
                    Json.obj(
                      "op"    -> "replace",
                      "path"  -> "/spec/servers/0/forwardPlugin/upstreams/0",
                      "value" -> "${responses.call-service[$.body.spec.clusterIP]}:5353"
                    )
                  )
                ),
                "success"   -> Json.obj(
                  "statuses" -> Json.arr(200)
                )
              )
            )
          )
        )
      )

      val resp = workflow.run(WorkFlowRequest.inline(Json.obj())).futureValue
      // println(Json.prettyPrint(resp.json))
    }

    "handle composition" in {
      val workflow = WorkFlow(
        WorkFlowSpec.inline(
          Json.obj(
            "name"        -> "test-workflow",
            "description" -> "",
            "tasks"       -> Json.arr(
              Json.obj(
                "name"     -> "call-service",
                "type"     -> "http",
                "request"  -> Json.obj(
                  "method"  -> "GET",
                  "url"     -> "http://${env.KUBERNETES_SERVICE_HOST}:${env.KUBERNETES_SERVICE_PORT}/apis/v1/services/otoroshi-dns",
                  "headers" -> Json.obj(
                    "accept"        -> "application/json",
                    "authorization" -> "Bearer ${file:///var/run/secrets/kubernetes.io/serviceaccount/token}"
                  ),
                  "tls"     -> Json.obj(
                    "mtls"     -> true,
                    "trustAll" -> true
                  )
                ),
                "success"  -> Json.obj(
                  "statuses" -> Json.arr(200)
                )
              ),
              Json.obj(
                "name"     -> "response",
                "type"     -> "compose-response",
                "response" -> Json.obj(
                  "foo"    -> "bar",
                  "status" -> Json.obj("$path" -> "$.responses.call-service.status"),
                  "value"  -> Json.obj("$path" -> "$.responses.call-service.body.spec")
                )
              )
            )
          )
        )
      )

      val resp = workflow.run(WorkFlowRequest.inline(Json.obj())).futureValue
      println(Json.prettyPrint(resp.json))
    }

    "shutdown" in {
      stopAll()
    }
  }
}
