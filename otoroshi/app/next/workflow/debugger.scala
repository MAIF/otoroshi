package otoroshi.next.workflow

import play.api.libs.json.Json

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}

object WorkflowDebugger {
  val debuggers = new TrieMap[String, WorkflowDebugger]
}

class WorkflowDebugger() {

  private val started: AtomicBoolean = new AtomicBoolean(false)
  private val runRef = new AtomicReference[WorkflowRun](null)
  private val promiseRef = new AtomicReference[Promise[Unit]](null)

  private def wfRun: Option[WorkflowRun] = Option(runRef.get())

  def initialize(wfr: WorkflowRun): Unit = {
    runRef.set(wfr)
    WorkflowDebugger.debuggers.put(wfr.id, this)
    started.set(true)
  }

  def shutdown(): Unit = {
    started.set(false)
    wfRun.foreach(wfr => WorkflowDebugger.debuggers.remove(wfr.id))
//    Option(promiseRef.get).foreach(_.trySuccess(()))
    promiseRef.set(null)
    runRef.set(null)
  }

  def pause(): Unit = {
    promiseRef.set(Promise[Unit]())
  }

  def next(): Unit = {
    if (started.get()) {
      val oldPromise = promiseRef.getAndSet(Promise[Unit]())
      if (oldPromise ne null) {
        oldPromise.trySuccess(())
      }
    }
  }

  def resume(): Unit = {
    if (started.get()) {
      val oldPromise = promiseRef.get()
      if (oldPromise ne null) {
        oldPromise.trySuccess(())
      }
    }
  }

  def waitForNextStep(): Future[Unit] = {
    if (started.get()) {
      val oldPromise = promiseRef.get()
      if (oldPromise ne null) {
        wfRun.foreach { wfr =>
          wfr.attrs.get(WorkflowAdminExtension.liveUpdatesSourceKey).foreach { source =>
            source.tryEmitNext(Json.obj("kind" -> "debugger-state", "data" -> wfr.json))
          }
        }
        oldPromise.future
      } else {
        Future.successful(())
      }
    } else {
      Future.successful(())
    }
  }
}
