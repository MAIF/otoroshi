package otoroshi.next.workflow

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object WorkflowTest {

  def test1(): Unit = {
    implicit val executorContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
    val env: Env                 = ???
    val engine                   = new WorkflowEngine(env)
    val workflow                 = Json
      .parse("""
         |{
         |  "id": "main",
         |  "kind": "workflow",
         |  "steps": [
         |    {
         |      "id": "hello",
         |      "kind": "call",
         |      "function": "core.hello",
         |      "args": {
         |        "name": "${input.name}"
         |      },
         |      "result": "call_res"
         |    },
         |    {
         |      "id": "foo1",
         |      "kind": "assign",
         |      "values": [
         |        {
         |          "name": "foo",
         |          "value": [
         |            "a",
         |            "b",
         |            "c"
         |          ]
         |        },
         |        {
         |          "name": "foo",
         |          "value": {
         |            "$array_append": {
         |              "array": "${foo}",
         |              "value": "d"
         |            }
         |          }
         |        },
         |        {
         |          "name": "foo",
         |          "value": {
         |            "$array_append": {
         |              "array": "${foo}",
         |              "value": "${input.name}"
         |            }
         |          }
         |        },
         |        {
         |          "name": "foo",
         |          "value": {
         |            "$array_append": {
         |              "array": "${foo}",
         |              "value": "${call_res}"
         |            }
         |          }
         |        }
         |      ]
         |    },
         |    {
         |      "id": "foo2",
         |      "kind": "assign",
         |      "values": [
         |        {
         |          "name": "final_res",
         |          "value": {
         |            "$array_page": {
         |              "array": "${foo}",
         |              "page": 1,
         |              "page_size": 3
         |            }
         |          }
         |        }
         |      ]
         |    },
         |    {
         |      "id": "foo3",
         |      "kind": "call",
         |      "function": "core.http_client",
         |      "args": {
         |        "method": "GET",
         |        "url": "https://pokeapi.co/api/v2/pokemon"
         |      },
         |      "result": "pokemons"
         |    },
         |    {
         |      "id": "foo4",
         |      "kind": "assign",
         |      "values": [
         |        {
         |          "name": "final_res",
         |          "value": {
         |            "$array_page": {
         |              "array": "${pokemons.body_json.results}",
         |              "page": 1,
         |              "page_size": 5
         |            }
         |          }
         |        }
         |      ]
         |    }
         |  ],
         |  "returned": {
         |    "$mem_ref": {
         |      "name": "final_res"
         |    }
         |  }
         |}
         |""".stripMargin)
      .asObject
    val node                     = Node.from(workflow)
    Files.writeString(new File("./workflow_test_1.json").toPath, workflow.prettify)
    engine.run(node, Json.obj("name" -> "foo")).map { res =>
      println(s"result: ${res.lightJson.prettify}")
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val executorContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
    val env: Env                 = ???
    val engine                   = new WorkflowEngine(env)
    val workflow                 = Json.obj(
      "id"       -> "main",
      "kind"     -> "workflow",
      "steps"    -> Json.arr(
        //Json.obj("id" -> "call_1", "kind" -> "call", "function" -> "core.log", "args" -> Json.obj("message" -> Json.obj("$mem_ref" -> Json.obj("name" -> "input", "path" -> "foo.bar"))), "result" -> "call_1"),
        Json.obj(
          "id"       -> "call_1",
          "kind"     -> "call",
          "function" -> "core.log",
          "args"     -> Json.obj("message" -> "${input.foo.bar}"),
          "result"   -> "call_1"
        ),
        Json.obj(
          "id"       -> "call_2",
          "kind"     -> "call",
          "function" -> "core.log",
          "args"     -> Json.obj("message" -> "step 2"),
          "result"   -> "call_2"
        ),
        Json.obj(
          "id"       -> "call_3",
          "kind"     -> "call",
          "function" -> "core.log",
          "args"     -> Json.obj("message" -> "step 3"),
          "result"   -> "call_3"
        )
      ),
      "returned" -> Json.obj("$mem_ref" -> Json.obj("name" -> "call_3"))
    )
    val node                     = Node.from(workflow)
    Files.writeString(new File("./workflow_test.json").toPath, workflow.prettify)
    engine.run(node, Json.obj("foo" -> Json.obj("bar" -> "qix"))).map { res =>
      println(s"result: ${res.lightJson.prettify}")
    }
  }
}
