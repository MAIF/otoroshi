# common-wasm

this library provides a runtime to execute wasm function in a pooled vm paradigm

## how to use it

first you need to have a class that implements the `WasmConfiguration` trait. This type represent a wasm vm you want to run. Objects that implements 
can be stored anywhere you want. This library provides a `BasicWasmConfiguration` implementation, but you can build your own. 

then create an integration context class that will provide access to everything needed

for instance, here is the otoroshi integration :

```scala
import io.otoroshi.common.wasm._

class OtoroshiWasmIntegrationContext(env: Env) extends WasmIntegrationContext {

  implicit val ec = env.otoroshiExecutionContext
  implicit val ev = env

  val logger: Logger = Logger("otoroshi-wasm-integration")
  val materializer: Materializer = env.otoroshiMaterializer
  val executionContext: ExecutionContext = env.otoroshiExecutionContext
  val wasmCacheTtl: Long = env.wasmCacheTtl
  val wasmQueueBufferSize: Int = env.wasmQueueBufferSize
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )

  override def url(path: String): WSRequest = env.Ws.url(path)

  override def mtlsUrl(path: String, tlsConfig: TlsConfig): WSRequest = {
    val cfg = NgTlsConfig.format.reads(tlsConfig.json).get.legacy
    env.MtlsWs.url(path, cfg)
  }

  override def wasmManagerSettings: Future[Option[WasmManagerSettings]] = env.datastores.globalConfigDataStore.latest().wasmManagerSettings.vfuture

  override def wasmConfig(path: String): Option[WasmConfiguration] = env.proxyState.wasmPlugin(path).map(_.config)

  override def wasmConfigs(): Seq[WasmConfiguration] = env.proxyState.allWasmPlugins().map(_.config)

  override def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = {
    HostFunctions.getFunctions(config.asInstanceOf[WasmConfig], pluginId, None)
  }
}
```

you can create one yourself like :

```scala
val testWasmConfigs: InMemoryWasmConfigurationStore[WasmConfiguration] = InMemoryWasmConfigurationStore(
"basic" -> BasicWasmConfiguration.fromWasiSource(WasmSource(WasmSourceKind.File, "./src/test/resources/basic.wasm")),
"opa" -> BasicWasmConfiguration.fromOpaSource(WasmSource(WasmSourceKind.File, "./src/test/resources/opa.wasm")),
)

class FooWasmIntegrationContext(env: Env) extends WasmIntegrationContext {
  val system = ActorSystem("foo-wasm")
  val materializer: Materializer = Materializer(system)
  val executionContext: ExecutionContext = system.dispatcher
  val logger: Logger = Logger("foo-wasm")
  val wasmCacheTtl: Long = 2000
  val wasmQueueBufferSize: Int = 100
  val wasmManagerSettings: Future[Option[WasmManagerSettings]] = Future.successful(None)
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )
  override def url(path: String): WSRequest = ??? // we do not provide http call right now ;)
  override def mtlsUrl(path: String, tlsConfig: TlsConfig): WSRequest = ???  // we do not provide http call right now ;)
  override def wasmConfig(path: String): Option[WasmConfiguration] = testWasmConfigs.wasmConfiguration(path)
  override def wasmConfigs(): Seq[WasmConfiguration] = testWasmConfigs.wasmConfigurations()
  override def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = Array.empty
}
```

then instanciate a wasm integration 

```scala
val wasmIntegration = WasmIntegration(new FooWasmIntegrationContext(env))
```

now you have to trigger jobs that will cache wasm stuff and clean vm. you can either do it manually or let the integration do it.

```scala
wasmIntegration.start()
Runtime.getRuntime().addShutdownHook(() => {
  wasmIntegration.stop()
})
```

now you can get a wasm vm through the wasm integration object and use it

```scala
wasmIntegration.withPooledVm(basicConfiguration) { vm =>
  vm.callExtismFunction(
    "execute",
    Json.obj("message" -> "coucou").stringify
  ).map {
    case Left(error) => println(s"error: ${error.prettify}")
    case Right(out) => {
      assertEquals(out, "{\"input\":{\"message\":\"coucou\"},\"message\":\"yo\"}")
      println(s"output: ${out}")
    }
  }
}
```
