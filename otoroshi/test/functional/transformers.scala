import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ForkJoinPool, ForkJoinWorkerThread}

object ForkJoinTry {

  val print = new Thread.UncaughtExceptionHandler() {
    def uncaughtException(thread: Thread, cause: Throwable) = {
      println(s"error on thread ${thread.getName}: ${cause.getMessage}")
      cause.printStackTrace()
    }
  }

  val factory = new ForkJoinPool.ForkJoinWorkerThreadFactory() {
    val counter = new AtomicLong(0L)
    override def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = {
      val worker = new ForkJoinWorkerThread(pool)
      worker.setName(s"my-thread-pool-thread-${counter.incrementAndGet()}")
      worker
    }
  }

  val parallelismMin: Int = 2
  val parallelismMax: Int = 10
  val parallelismFactor: Double = 2.0
  val parallelism: Int = math.min(math.max((Runtime.getRuntime.availableProcessors * parallelismFactor).ceil.toInt, parallelismMin), parallelismMax)

  val pool = new ForkJoinPool(parallelism, factory, print, true)

}