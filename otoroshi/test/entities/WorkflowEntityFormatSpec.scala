package entities

import otoroshi.models.EntityLocation
import otoroshi.next.workflow.*
import otoroshi.script.{JobId, JobInstantiation, JobKind}
import play.api.libs.json.*

import scala.concurrent.duration.*

class WorkflowEntityFormatSpec extends EntityFormatSpec {

  private val defaultJobJson = Json.obj(
    "enabled"         -> false,
    "kind"            -> "ScheduledEvery",
    "instantiation"   -> "OneInstancePerOtoroshiInstance",
    "initial_delay"   -> JsNull,
    "interval"        -> JsNull,
    "cron_expression" -> JsNull,
    "config"          -> Json.obj()
  )

  private val fullJob = WorkflowJobConfig(
    enabled = true,
    kind = JobKind.Cron,
    instantiation = JobInstantiation.OneInstancePerOtoroshiLeaderInstance,
    initialDelay = Some(5.seconds),
    interval = Some(1.hour),
    cronExpression = Some("0 0 * * * ?"),
    rawConfig = Json.obj("name" -> "job", "count" -> 3)
  )

  private val noteJson = Json.obj(
    "content"    -> "remember to add a retry",
    "color"      -> "#ffeb3b",
    "titleColor" -> "#000",
    "position"   -> Json.obj("x" -> 120, "y" -> -40.5),
    "measured"   -> Json.obj("width" -> 200, "height" -> 80),
    "id"         -> "note-1",
    "kind"       -> "note"
  )

  private val sampleNote = Note(
    content = "remember to add a retry",
    color = "#ffeb3b",
    titleColor = "#000",
    position = Json.obj("x" -> 120, "y" -> -40.5),
    measured = Json.obj("width" -> 200, "height" -> 80),
    id = "note-1",
    kind = "note"
  )

  private val orphanNodeJson = Json.obj(
    "id"       -> "orphan-1",
    "kind"     -> "call",
    "function" -> "core.log",
    "args"     -> Json.obj("message" -> "hello"),
    "position" -> Json.obj("x" -> 1, "y" -> 2)
  )

  private val edgeJson = Json.obj("id" -> "edge-1", "source" -> "main", "target" -> "orphan-1")

  private val minimal = Workflow(
    location = EntityLocation(),
    id = "workflow-1",
    name = "Workflow 1",
    description = "",
    tags = Seq.empty,
    metadata = Map.empty,
    config = Json.obj(),
    job = WorkflowJobConfig.default,
    functions = Map.empty,
    testPayload = Json.obj(),
    orphans = Orphans()
  )

  private val full = Workflow(
    location = customLocation,
    id = "workflow-1",
    name = "Workflow 1",
    description = "says hello",
    tags = Seq("hello", "demo"),
    metadata = Map("owner" -> "ops", "version" -> "2"),
    config = Node.default,
    job = fullJob,
    functions = Map(
      "greet" -> Json.obj("kind" -> "call", "function" -> "core.hello", "args" -> Json.obj("name" -> "${input.name}")),
      "noop"  -> Json.obj("kind" -> "noop")
    ),
    testPayload = Json.obj("name" -> "bar", "nested" -> Json.obj("list" -> Json.arr(1, 2))),
    orphans = Orphans(Seq(Node.from(orphanNodeJson)), Seq(edgeJson)),
    notes = Seq(sampleNote, sampleNote.copy(id = "note-2", content = "", color = "red"))
  )

  /** the json of a workflow with the given fields, the rest taken from the minimal workflow */
  private def minimalJson(fields: (String, Json.JsValueWrapper)*): JsObject =
    written(Workflow.format, minimal) ++ Json.obj(fields*)

  "Workflow" should {

    val format = Workflow.format

    "write a minimal workflow" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"         -> defaultLocationJson,
        "id"           -> "workflow-1",
        "name"         -> "Workflow 1",
        "description"  -> "",
        "metadata"     -> Json.obj(),
        "tags"         -> Json.arr(),
        "config"       -> Json.obj(),
        "test_payload" -> Json.obj(),
        "orphans"      -> Json.obj("nodes" -> Json.arr(), "edges" -> Json.arr()),
        "job"          -> defaultJobJson,
        "functions"    -> Json.obj(),
        "notes"        -> Json.arr()
      )
    }

    "write the same keys whatever the fields hold" in {
      writtenKeys(format, full) mustBe writtenKeys(format, minimal)
    }

    "write the config, the functions and the test payload as they are" in {
      val json = written(format, full)
      (json \ "config").get mustBe Node.default
      (json \ "functions").get mustBe Json.obj(
        "greet" -> Json.obj("kind" -> "call", "function" -> "core.hello", "args" -> Json.obj("name" -> "${input.name}")),
        "noop"  -> Json.obj("kind" -> "noop")
      )
      (json \ "test_payload").get mustBe full.testPayload
    }

    "write the orphan nodes and edges" in {
      (written(format, full) \ "orphans").get mustBe Json.obj("nodes" -> Json.arr(orphanNodeJson), "edges" -> Json.arr(edgeJson))
    }

    "write the notes" in {
      (written(format, full) \ "notes").as[JsArray].value.head mustBe noteJson
    }

    "round trip a minimal workflow" in {
      roundTrip(format, minimal)
    }

    "round trip a workflow where every field is set" in {
      roundTrip(format, full)
    }

    "keep the order of the notes" in {
      roundTrip(format, full).notes.map(_.id) mustBe Seq("note-1", "note-2")
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a workflow without _loc in the default location" in {
      readOk(format, minimalJson() - "_loc").location mustBe EntityLocation()
    }

    "read a lone id, name and description with the defaults for the rest" in {
      readOk(format, Json.obj("id" -> "w", "name" -> "W", "description" -> "")) mustBe Workflow(
        location = EntityLocation(),
        id = "w",
        name = "W",
        description = "",
        tags = Seq.empty,
        metadata = Map.empty,
        config = Json.obj(),
        job = WorkflowJobConfig.default,
        functions = Map.empty,
        testPayload = Json.obj("name" -> "foo"),
        orphans = Orphans(),
        notes = Seq.empty
      )
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a workflow without id" in {
      readError(format, minimalJson() - "id")
    }

    "not read a workflow without name" in {
      readError(format, minimalJson() - "name")
    }

    "not read a workflow without description" in {
      readError(format, minimalJson() - "description")
    }

    "not read a name that is not a string" in {
      readError(format, minimalJson("name" -> Json.arr("Workflow 1")))
    }

    "read a workflow without config with an empty config" in {
      readOk(format, written(format, full) - "config").config mustBe Json.obj()
    }

    "read a config given as an array of nodes as an empty config" in {
      readOk(format, minimalJson("config" -> Json.arr(Json.obj("kind" -> "noop")))).config mustBe Json.obj()
    }

    "read a workflow without test payload with the default payload" in {
      readOk(format, minimalJson() - "test_payload").testPayload mustBe Json.obj("name" -> "foo")
    }

    "read a test payload that is not an object as the default payload" in {
      readOk(format, minimalJson("test_payload" -> "foo")).testPayload mustBe Json.obj("name" -> "foo")
    }

    "not read the test payload under its camel case key" in {
      readOk(format, (minimalJson() - "test_payload") ++ Json.obj("testPayload" -> Json.obj("a" -> 1))).testPayload mustBe Json.obj(
        "name" -> "foo"
      )
    }

    "read a workflow without job with the default job" in {
      readOk(format, written(format, full) - "job").job mustBe WorkflowJobConfig.default
    }

    "read a job that is not an object as the default job" in {
      readOk(format, minimalJson("job" -> true)).job mustBe WorkflowJobConfig.default
    }

    "read a job without kind as the default, disabled, job" in {
      readOk(format, minimalJson("job" -> (defaultJobJson ++ Json.obj("enabled" -> true) - "kind"))).job mustBe WorkflowJobConfig.default
    }

    "read a job without instantiation as the default, disabled, job" in {
      readOk(format, minimalJson("job" -> Json.obj("enabled" -> true, "kind" -> "Cron", "cron_expression" -> "* * * * * ?"))).job mustBe
        WorkflowJobConfig.default
    }

    "read a workflow without functions with no function" in {
      readOk(format, written(format, full) - "functions").functions mustBe empty
    }

    "drop every function when one of them is not an object" in {
      readOk(format, minimalJson("functions" -> Json.obj("greet" -> Json.obj("kind" -> "noop"), "bad" -> "core.hello"))).functions mustBe empty
    }

    "read a workflow without orphans with no orphan" in {
      readOk(format, written(format, full) - "orphans").orphans mustBe Orphans()
    }

    "read orphans that are not an object as no orphan" in {
      readOk(format, minimalJson("orphans" -> Json.arr(orphanNodeJson))).orphans mustBe Orphans()
    }

    "read a workflow without notes with no note" in {
      readOk(format, written(format, full) - "notes").notes mustBe empty
    }

    "read notes that are not an array as no note" in {
      readOk(format, minimalJson("notes" -> noteJson)).notes mustBe empty
    }

    "drop every note when one of them cannot be read" in {
      readOk(format, minimalJson("notes" -> Json.arr(noteJson, noteJson - "id"))).notes mustBe empty
    }

    "read a workflow without tags nor metadata with empty ones" in {
      val back = readOk(format, written(format, full) - "tags" - "metadata")
      back.tags mustBe empty
      back.metadata mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson("tags" -> "hello")).tags mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, minimalJson("foo" -> "bar")) mustBe minimal
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "workflow-1", "Workflow 1", "says hello", Seq("hello", "demo"), Map("owner" -> "ops", "version" -> "2"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "build a template running the default hello workflow" in {
      val template = Workflow.template()
      template.id must startWith("workflow_")
      template.location mustBe EntityLocation.default
      template.config mustBe Node.default
      template.job mustBe WorkflowJobConfig.default
      template.testPayload mustBe Json.obj("name" -> "foo")
      template.notes mustBe empty
    }

    "build templates with distinct ids" in {
      Workflow.template().id must not be Workflow.template().id
    }

    "round trip its template" in {
      roundTrip(format, Workflow.template())
    }
  }

  "WorkflowJobConfig" should {

    val format = WorkflowJobConfig.format

    "be disabled, scheduled every and per otoroshi instance by default" in {
      WorkflowJobConfig.default mustBe WorkflowJobConfig(
        enabled = false,
        kind = JobKind.ScheduledEvery,
        instantiation = JobInstantiation.OneInstancePerOtoroshiInstance,
        initialDelay = None,
        interval = None,
        cronExpression = None,
        rawConfig = Json.obj()
      )
    }

    "write the default job with nulls for the missing durations and cron" in {
      format.writes(WorkflowJobConfig.default) mustBe defaultJobJson
    }

    "write a job where every field is set" in {
      format.writes(fullJob) mustBe Json.obj(
        "enabled"         -> true,
        "kind"            -> "Cron",
        "instantiation"   -> "OneInstancePerOtoroshiLeaderInstance",
        "initial_delay"   -> 5000,
        "interval"        -> 3600000,
        "cron_expression" -> "0 0 * * * ?",
        "config"          -> Json.obj("name" -> "job", "count" -> 3)
      )
    }

    "expose through json what the format writes" in {
      fullJob.json mustBe format.writes(fullJob)
    }

    "round trip the default job" in {
      roundTrip(format, WorkflowJobConfig.default)
    }

    "round trip a job where every field is set" in {
      roundTrip(format, fullJob)
    }

    "round trip each job kind" in {
      Seq(JobKind.ScheduledOnce, JobKind.ScheduledEvery, JobKind.Cron, JobKind.Autonomous).foreach { kind =>
        roundTrip(format, fullJob.copy(kind = kind)).kind mustBe kind
      }
    }

    "round trip each instantiation" in {
      Seq(
        JobInstantiation.OneInstancePerOtoroshiInstance,
        JobInstantiation.OneInstancePerOtoroshiWorkerInstance,
        JobInstantiation.OneInstancePerOtoroshiLeaderInstance,
        JobInstantiation.OneInstancePerOtoroshiCluster
      ).foreach { instantiation =>
        roundTrip(format, fullJob.copy(instantiation = instantiation)).instantiation mustBe instantiation
      }
    }

    "read the durations as milliseconds" in {
      val back = readOk(format, defaultJobJson ++ Json.obj("initial_delay" -> 250, "interval" -> 60000))
      back.initialDelay mustBe Some(250.millis)
      back.interval mustBe Some(1.minute)
    }

    "drop the sub millisecond part of the durations" in {
      readOk(format, format.writes(fullJob.copy(interval = Some(2500.micros)))).interval mustBe Some(2.millis)
    }

    "read durations written as strings as missing" in {
      val back = readOk(format, defaultJobJson ++ Json.obj("initial_delay" -> "5s", "interval" -> "1000"))
      back.initialDelay mustBe None
      back.interval mustBe None
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a job without kind" in {
      readError(format, defaultJobJson - "kind")
    }

    "not read a job without instantiation" in {
      readError(format, defaultJobJson - "instantiation")
    }

    "not read a kind that is not a string" in {
      readError(format, defaultJobJson ++ Json.obj("kind" -> 1))
    }

    "read the kind and the instantiation whatever their case" in {
      val back = readOk(format, Json.obj("kind" -> "scheduledonce", "instantiation" -> "ONEINSTANCEPEROTOROSHIWORKERINSTANCE"))
      back.kind mustBe JobKind.ScheduledOnce
      back.instantiation mustBe JobInstantiation.OneInstancePerOtoroshiWorkerInstance
    }

    "read an unknown kind as scheduled every" in {
      readOk(format, defaultJobJson ++ Json.obj("kind" -> "weekly")).kind mustBe JobKind.ScheduledEvery
    }

    "read an unknown instantiation as one instance per otoroshi instance" in {
      readOk(format, defaultJobJson ++ Json.obj("instantiation" -> "anywhere")).instantiation mustBe
        JobInstantiation.OneInstancePerOtoroshiInstance
    }

    "read a lone kind and instantiation with the defaults for the rest" in {
      readOk(format, Json.obj("kind" -> "Cron", "instantiation" -> "OneInstancePerOtoroshiCluster")) mustBe WorkflowJobConfig(
        kind = JobKind.Cron,
        instantiation = JobInstantiation.OneInstancePerOtoroshiCluster
      )
    }

    "read an enabled flag written as a string as disabled" in {
      readOk(format, defaultJobJson ++ Json.obj("enabled" -> "true")).enabled mustBe false
    }

    "read a raw config that is not an object as an empty one" in {
      readOk(format, defaultJobJson ++ Json.obj("config" -> "foo")).rawConfig mustBe Json.obj()
    }

    "not read the raw config under its field name" in {
      readOk(format, defaultJobJson - "config" ++ Json.obj("rawConfig" -> Json.obj("a" -> 1))).rawConfig mustBe Json.obj()
    }
  }

  "WorkflowJob" should {

    "be identified by the workflow it runs" in {
      new WorkflowJob("workflow-1", fullJob).uniqueId mustBe JobId("io.otoroshi.next.workflow.WorkflowJob#workflow-1")
    }

    "run with the kind of the workflow job config" in {
      new WorkflowJob("workflow-1", fullJob).kind mustBe JobKind.Cron
    }

    "give the default job config as default config" in {
      new WorkflowJob("workflow-1", fullJob).defaultConfig mustBe Some(defaultJobJson)
    }
  }

  "Note" should {

    val format = Note.format

    "write a note" in {
      format.writes(sampleNote) mustBe noteJson
    }

    "round trip a note" in {
      roundTrip(format, sampleNote)
    }

    "round trip a note without content" in {
      roundTrip(format, sampleNote.copy(content = "")).content mustBe ""
    }

    "read a note without content, color nor title color with their defaults" in {
      val back = readOk(format, noteJson - "content" - "color" - "titleColor")
      back.content mustBe ""
      back.color mustBe "var(--bg-color_level3)"
      back.titleColor mustBe "#fff"
    }

    "read a color that is not a string as the default color" in {
      readOk(format, noteJson ++ Json.obj("color" -> 1)).color mustBe "var(--bg-color_level3)"
    }

    "not read the title color under a snake case key" in {
      readOk(format, noteJson - "titleColor" ++ Json.obj("title_color" -> "#000")).titleColor mustBe "#fff"
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a note without id" in {
      readError(format, noteJson - "id")
    }

    "not read a note without kind" in {
      readError(format, noteJson - "kind")
    }

    "not read a note without position" in {
      readError(format, noteJson - "position")
    }

    "not read a note without measured size" in {
      readError(format, noteJson - "measured")
    }

    "not read a position that is not an object" in {
      readError(format, noteJson ++ Json.obj("position" -> Json.arr(120, 40)))
    }
  }

  "Orphans" should {

    val format = Orphans.format

    "have no node nor edge by default" in {
      Orphans() mustBe Orphans(Seq.empty, Seq.empty)
    }

    "write no orphan" in {
      format.writes(Orphans()) mustBe Json.obj("nodes" -> Json.arr(), "edges" -> Json.arr())
    }

    "write the nodes as their json" in {
      format.writes(Orphans(Seq(Node.from(orphanNodeJson)), Seq.empty)) mustBe Json.obj(
        "nodes" -> Json.arr(orphanNodeJson),
        "edges" -> Json.arr()
      )
    }

    "round trip no orphan" in {
      roundTrip(format, Orphans())
    }

    "round trip orphan nodes and edges" in {
      roundTrip(format, Orphans(Seq(Node.from(orphanNodeJson), Node.from(Json.obj("kind" -> "noop"))), Seq(edgeJson)))
    }

    "keep an orphan node of an unknown kind as it is" in {
      val unknown = Json.obj("id" -> "x", "kind" -> "not-a-node", "foo" -> "bar")
      readOk(format, Json.obj("nodes" -> Json.arr(unknown))).nodes.map(_.json) mustBe Seq(unknown)
    }

    "keep an orphan node without kind as it is" in {
      val noKind = Json.obj("id" -> "x")
      readOk(format, Json.obj("nodes" -> Json.arr(noKind))).nodes.map(_.json) mustBe Seq(noKind)
    }

    "read an empty object as no orphan" in {
      readOk(format, Json.obj()) mustBe Orphans()
    }

    "read a json null as no orphan" in {
      readOk(format, JsNull) mustBe Orphans()
    }

    "drop every node when one of them is not an object" in {
      readOk(format, Json.obj("nodes" -> Json.arr(orphanNodeJson, "orphan-2"))).nodes mustBe empty
    }

    "drop every edge when one of them is not an object" in {
      readOk(format, Json.obj("edges" -> Json.arr(edgeJson, 1))).edges mustBe empty
    }

    "read the edges without the nodes" in {
      readOk(format, Json.obj("edges" -> Json.arr(edgeJson))) mustBe Orphans(Seq.empty, Seq(edgeJson))
    }
  }
}
