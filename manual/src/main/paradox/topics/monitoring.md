# Monitoring Otoroshi

The Otoroshi API exposes two endpoints for 

* `/health`: the health of the Otoroshi instance
* `/metrics`: the metrics of the Otoroshi instance, either in JSON or Prometheus format using the `Accept` header (with `application/json` / `application/prometheus` values) or the `format` query param (with `json` or `prometheus` values)
* `/live`: returns an http 200 response `{"live": true}` when the service is alive
* `/ready`: return an http 200 response `{"ready": true}` when the instance is ready to accept traffic (certs synced, plugins compiled, etc). if not, returns http 503 `{"ready": false}`
* `/startup`: return an http 200 response `{"started": true}` when the instance is ready to accept traffic (certs synced, plugins compiled, etc). if not, returns http 503 `{"started": false}`

## Endpoints security

The two endpoints are exposed publicly on the Otoroshi admin api. But you can remove the corresponding public pattern and query the endpoints using standard apikeys. If you don't want to use apikeys but don't want to expose the endpoints publicly, you can defined two config. variables (`app.health.accessKey` or `HEALTH_ACCESS_KEY` and `otoroshi.metrics.accessKey` or `OTOROSHI_METRICS_ACCESS_KEY`) that will hold an access key for the endpoints. Then you can call the endpoints with an `access_key` query param with the value defined in the config. If you don't defined `otoroshi.metrics.accessKey` but define `app.health.accessKey`, `otoroshi.metrics.accessKey` will have the value of `app.health.accessKey`.
 
## Examples

let say `app.health.accessKey` has value `MILpkVv6f2kG9Xmnc4mFIYRU4rTxHVGkxvB0hkQLZwEaZgE2hgbOXiRsN1DBnbtY`

```sh
$ curl http://otoroshi-api.oto.tools:8080/health\?access_key\=MILpkVv6f2kG9Xmnc4mFIYRU4rTxHVGkxvB0hkQLZwEaZgE2hgbOXiRsN1DBnbtY
{"otoroshi":"healthy","datastore":"healthy"}

$ curl -H 'Accept: application/json' http://otoroshi-api.oto.tools:8080/metrics\?access_key\=MILpkVv6f2kG9Xmnc4mFIYRU4rTxHVGkxvB0hkQLZwEaZgE2hgbOXiRsN1DBnbtY
{"version":"4.0.0","gauges":{"attr.app.commit":{"value":"xxxx"},"attr.app.id":{"value":"xxxx"},"attr.cluster.mode":{"value":"Leader"},"attr.cluster.name":{"value":"otoroshi-leader-0"},"attr.instance.env":{"value":"prod"},"attr.instance.id":{"value":"xxxx"},"attr.instance.number":{"value":"0"},"attr.jvm.cpu.usage":{"value":136},"attr.jvm.heap.size":{"value":1409},"attr.jvm.heap.used":{"value":112},"internals.0.concurrent-requests":{"value":1},"internals.global.throttling-quotas":{"value":2},"jvm.attr.name":{"value":"2085@xxxx"},"jvm.attr.uptime":{"value":2296900},"jvm.attr.vendor":{"value":"JDK11"},"jvm.gc.PS-MarkSweep.count":{"value":3},"jvm.gc.PS-MarkSweep.time":{"value":261},"jvm.gc.PS-Scavenge.count":{"value":12},"jvm.gc.PS-Scavenge.time":{"value":161},"jvm.memory.heap.committed":{"value":1477967872},"jvm.memory.heap.init":{"value":1690304512},"jvm.memory.heap.max":{"value":3005218816},"jvm.memory.heap.usage":{"value":0.03916456777568639},"jvm.memory.heap.used":{"value":117698096},"jvm.memory.non-heap.committed":{"value":166445056},"jvm.memory.non-heap.init":{"value":7667712},"jvm.memory.non-heap.max":{"value":994050048},"jvm.memory.non-heap.usage":{"value":0.1523920694986979},"jvm.memory.non-heap.used":{"value":151485344},"jvm.memory.pools.CodeHeap-'non-nmethods'.committed":{"value":2555904},"jvm.memory.pools.CodeHeap-'non-nmethods'.init":{"value":2555904},"jvm.memory.pools.CodeHeap-'non-nmethods'.max":{"value":5832704},"jvm.memory.pools.CodeHeap-'non-nmethods'.usage":{"value":0.28408093398876405},"jvm.memory.pools.CodeHeap-'non-nmethods'.used":{"value":1656960},"jvm.memory.pools.CodeHeap-'non-profiled-nmethods'.committed":{"value":11796480},"jvm.memory.pools.CodeHeap-'non-profiled-nmethods'.init":{"value":2555904},"jvm.memory.pools.CodeHeap-'non-profiled-nmethods'.max":{"value":122912768},"jvm.memory.pools.CodeHeap-'non-profiled-nmethods'.usage":{"value":0.09536102872567315},"jvm.memory.pools.CodeHeap-'non-profiled-nmethods'.used":{"value":11721088},"jvm.memory.pools.CodeHeap-'profiled-nmethods'.committed":{"value":37355520},"jvm.memory.pools.CodeHeap-'profiled-nmethods'.init":{"value":2555904},"jvm.memory.pools.CodeHeap-'profiled-nmethods'.max":{"value":122912768},"jvm.memory.pools.CodeHeap-'profiled-nmethods'.usage":{"value":0.2538573047187417},"jvm.memory.pools.CodeHeap-'profiled-nmethods'.used":{"value":31202304},"jvm.memory.pools.Compressed-Class-Space.committed":{"value":14942208},"jvm.memory.pools.Compressed-Class-Space.init":{"value":0},"jvm.memory.pools.Compressed-Class-Space.max":{"value":367001600},"jvm.memory.pools.Compressed-Class-Space.usage":{"value":0.033858838762555805},"jvm.memory.pools.Compressed-Class-Space.used":{"value":12426248},"jvm.memory.pools.Metaspace.committed":{"value":99794944},"jvm.memory.pools.Metaspace.init":{"value":0},"jvm.memory.pools.Metaspace.max":{"value":375390208},"jvm.memory.pools.Metaspace.usage":{"value":0.25168142904782426},"jvm.memory.pools.Metaspace.used":{"value":94478744},"jvm.memory.pools.PS-Eden-Space.committed":{"value":349700096},"jvm.memory.pools.PS-Eden-Space.init":{"value":422576128},"jvm.memory.pools.PS-Eden-Space.max":{"value":1110966272},"jvm.memory.pools.PS-Eden-Space.usage":{"value":0.07505125052077188},"jvm.memory.pools.PS-Eden-Space.used":{"value":83379408},"jvm.memory.pools.PS-Eden-Space.used-after-gc":{"value":0},"jvm.memory.pools.PS-Old-Gen.committed":{"value":1127219200},"jvm.memory.pools.PS-Old-Gen.init":{"value":1127219200},"jvm.memory.pools.PS-Old-Gen.max":{"value":2253914112},"jvm.memory.pools.PS-Old-Gen.usage":{"value":0.014950035505168354},"jvm.memory.pools.PS-Old-Gen.used":{"value":33696096},"jvm.memory.pools.PS-Old-Gen.used-after-gc":{"value":23791152},"jvm.memory.pools.PS-Survivor-Space.committed":{"value":1048576},"jvm.memory.pools.PS-Survivor-Space.init":{"value":70254592},"jvm.memory.pools.PS-Survivor-Space.max":{"value":1048576},"jvm.memory.pools.PS-Survivor-Space.usage":{"value":0.59375},"jvm.memory.pools.PS-Survivor-Space.used":{"value":622592},"jvm.memory.pools.PS-Survivor-Space.used-after-gc":{"value":622592},"jvm.memory.total.committed":{"value":1644412928},"jvm.memory.total.init":{"value":1697972224},"jvm.memory.total.max":{"value":3999268864},"jvm.memory.total.used":{"value":269184904},"jvm.thread.blocked.count":{"value":0},"jvm.thread.count":{"value":82},"jvm.thread.daemon.count":{"value":11},"jvm.thread.deadlock.count":{"value":0},"jvm.thread.deadlocks":{"value":[]},"jvm.thread.new.count":{"value":0},"jvm.thread.runnable.count":{"value":25},"jvm.thread.terminated.count":{"value":0},"jvm.thread.timed_waiting.count":{"value":10},"jvm.thread.waiting.count":{"value":47}},"counters":{},"histograms":{},"meters":{},"timers":{}}

$ curl -H 'Accept: application/prometheus' http://otoroshi-api.oto.tools:8080/metrics\?access_key\=MILpkVv6f2kG9Xmnc4mFIYRU4rTxHVGkxvB0hkQLZwEaZgE2hgbOXiRsN1DBnbtY
# TYPE attr_jvm_cpu_usage gauge
attr_jvm_cpu_usage 83.0
# TYPE attr_jvm_heap_size gauge
attr_jvm_heap_size 1409.0
# TYPE attr_jvm_heap_used gauge
attr_jvm_heap_used 220.0
# TYPE internals_0_concurrent_requests gauge
internals_0_concurrent_requests 1.0
# TYPE internals_global_throttling_quotas gauge
internals_global_throttling_quotas 3.0
# TYPE jvm_attr_uptime gauge
jvm_attr_uptime 2372614.0
# TYPE jvm_gc_PS_MarkSweep_count gauge
jvm_gc_PS_MarkSweep_count 3.0
# TYPE jvm_gc_PS_MarkSweep_time gauge
jvm_gc_PS_MarkSweep_time 261.0
# TYPE jvm_gc_PS_Scavenge_count gauge
jvm_gc_PS_Scavenge_count 12.0
# TYPE jvm_gc_PS_Scavenge_time gauge
jvm_gc_PS_Scavenge_time 161.0
# TYPE jvm_memory_heap_committed gauge
jvm_memory_heap_committed 1.477967872E9
# TYPE jvm_memory_heap_init gauge
jvm_memory_heap_init 1.690304512E9
# TYPE jvm_memory_heap_max gauge
jvm_memory_heap_max 3.005218816E9
# TYPE jvm_memory_heap_usage gauge
jvm_memory_heap_usage 0.07680553268571043
# TYPE jvm_memory_heap_used gauge
jvm_memory_heap_used 2.30817432E8
# TYPE jvm_memory_non_heap_committed gauge
jvm_memory_non_heap_committed 1.66510592E8
# TYPE jvm_memory_non_heap_init gauge
jvm_memory_non_heap_init 7667712.0
# TYPE jvm_memory_non_heap_max gauge
jvm_memory_non_heap_max 9.94050048E8
# TYPE jvm_memory_non_heap_usage gauge
jvm_memory_non_heap_usage 0.15262878997416435
# TYPE jvm_memory_non_heap_used gauge
jvm_memory_non_heap_used 1.51720656E8
# TYPE jvm_memory_pools_CodeHeap__non_nmethods__committed gauge
jvm_memory_pools_CodeHeap__non_nmethods__committed 2555904.0
# TYPE jvm_memory_pools_CodeHeap__non_nmethods__init gauge
jvm_memory_pools_CodeHeap__non_nmethods__init 2555904.0
# TYPE jvm_memory_pools_CodeHeap__non_nmethods__max gauge
jvm_memory_pools_CodeHeap__non_nmethods__max 5832704.0
# TYPE jvm_memory_pools_CodeHeap__non_nmethods__usage gauge
jvm_memory_pools_CodeHeap__non_nmethods__usage 0.28408093398876405
# TYPE jvm_memory_pools_CodeHeap__non_nmethods__used gauge
jvm_memory_pools_CodeHeap__non_nmethods__used 1656960.0
# TYPE jvm_memory_pools_CodeHeap__non_profiled_nmethods__committed gauge
jvm_memory_pools_CodeHeap__non_profiled_nmethods__committed 1.1862016E7
# TYPE jvm_memory_pools_CodeHeap__non_profiled_nmethods__init gauge
jvm_memory_pools_CodeHeap__non_profiled_nmethods__init 2555904.0
# TYPE jvm_memory_pools_CodeHeap__non_profiled_nmethods__max gauge
jvm_memory_pools_CodeHeap__non_profiled_nmethods__max 1.22912768E8
# TYPE jvm_memory_pools_CodeHeap__non_profiled_nmethods__usage gauge
jvm_memory_pools_CodeHeap__non_profiled_nmethods__usage 0.09610562183417755
# TYPE jvm_memory_pools_CodeHeap__non_profiled_nmethods__used gauge
jvm_memory_pools_CodeHeap__non_profiled_nmethods__used 1.1812608E7
# TYPE jvm_memory_pools_CodeHeap__profiled_nmethods__committed gauge
jvm_memory_pools_CodeHeap__profiled_nmethods__committed 3.735552E7
# TYPE jvm_memory_pools_CodeHeap__profiled_nmethods__init gauge
jvm_memory_pools_CodeHeap__profiled_nmethods__init 2555904.0
# TYPE jvm_memory_pools_CodeHeap__profiled_nmethods__max gauge
jvm_memory_pools_CodeHeap__profiled_nmethods__max 1.22912768E8
# TYPE jvm_memory_pools_CodeHeap__profiled_nmethods__usage gauge
jvm_memory_pools_CodeHeap__profiled_nmethods__usage 0.25493618368435084
# TYPE jvm_memory_pools_CodeHeap__profiled_nmethods__used gauge
jvm_memory_pools_CodeHeap__profiled_nmethods__used 3.1334912E7
# TYPE jvm_memory_pools_Compressed_Class_Space_committed gauge
jvm_memory_pools_Compressed_Class_Space_committed 1.4942208E7
# TYPE jvm_memory_pools_Compressed_Class_Space_init gauge
jvm_memory_pools_Compressed_Class_Space_init 0.0
# TYPE jvm_memory_pools_Compressed_Class_Space_max gauge
jvm_memory_pools_Compressed_Class_Space_max 3.670016E8
# TYPE jvm_memory_pools_Compressed_Class_Space_usage gauge
jvm_memory_pools_Compressed_Class_Space_usage 0.03386023385184152
# TYPE jvm_memory_pools_Compressed_Class_Space_used gauge
jvm_memory_pools_Compressed_Class_Space_used 1.242676E7
# TYPE jvm_memory_pools_Metaspace_committed gauge
jvm_memory_pools_Metaspace_committed 9.9794944E7
# TYPE jvm_memory_pools_Metaspace_init gauge
jvm_memory_pools_Metaspace_init 0.0
# TYPE jvm_memory_pools_Metaspace_max gauge
jvm_memory_pools_Metaspace_max 3.75390208E8
# TYPE jvm_memory_pools_Metaspace_usage gauge
jvm_memory_pools_Metaspace_usage 0.25170985813247426
# TYPE jvm_memory_pools_Metaspace_used gauge
jvm_memory_pools_Metaspace_used 9.4489416E7
# TYPE jvm_memory_pools_PS_Eden_Space_committed gauge
jvm_memory_pools_PS_Eden_Space_committed 3.49700096E8
# TYPE jvm_memory_pools_PS_Eden_Space_init gauge
jvm_memory_pools_PS_Eden_Space_init 4.22576128E8
# TYPE jvm_memory_pools_PS_Eden_Space_max gauge
jvm_memory_pools_PS_Eden_Space_max 1.110966272E9
# TYPE jvm_memory_pools_PS_Eden_Space_usage gauge
jvm_memory_pools_PS_Eden_Space_usage 0.17698545577448457
# TYPE jvm_memory_pools_PS_Eden_Space_used gauge
jvm_memory_pools_PS_Eden_Space_used 1.96624872E8
# TYPE jvm_memory_pools_PS_Eden_Space_used_after_gc gauge
jvm_memory_pools_PS_Eden_Space_used_after_gc 0.0
# TYPE jvm_memory_pools_PS_Old_Gen_committed gauge
jvm_memory_pools_PS_Old_Gen_committed 1.1272192E9
# TYPE jvm_memory_pools_PS_Old_Gen_init gauge
jvm_memory_pools_PS_Old_Gen_init 1.1272192E9
# TYPE jvm_memory_pools_PS_Old_Gen_max gauge
jvm_memory_pools_PS_Old_Gen_max 2.253914112E9
# TYPE jvm_memory_pools_PS_Old_Gen_usage gauge
jvm_memory_pools_PS_Old_Gen_usage 0.014950035505168354
# TYPE jvm_memory_pools_PS_Old_Gen_used gauge
jvm_memory_pools_PS_Old_Gen_used 3.3696096E7
# TYPE jvm_memory_pools_PS_Old_Gen_used_after_gc gauge
jvm_memory_pools_PS_Old_Gen_used_after_gc 2.3791152E7
# TYPE jvm_memory_pools_PS_Survivor_Space_committed gauge
jvm_memory_pools_PS_Survivor_Space_committed 1048576.0
# TYPE jvm_memory_pools_PS_Survivor_Space_init gauge
jvm_memory_pools_PS_Survivor_Space_init 7.0254592E7
# TYPE jvm_memory_pools_PS_Survivor_Space_max gauge
jvm_memory_pools_PS_Survivor_Space_max 1048576.0
# TYPE jvm_memory_pools_PS_Survivor_Space_usage gauge
jvm_memory_pools_PS_Survivor_Space_usage 0.59375
# TYPE jvm_memory_pools_PS_Survivor_Space_used gauge
jvm_memory_pools_PS_Survivor_Space_used 622592.0
# TYPE jvm_memory_pools_PS_Survivor_Space_used_after_gc gauge
jvm_memory_pools_PS_Survivor_Space_used_after_gc 622592.0
# TYPE jvm_memory_total_committed gauge
jvm_memory_total_committed 1.644478464E9
# TYPE jvm_memory_total_init gauge
jvm_memory_total_init 1.697972224E9
# TYPE jvm_memory_total_max gauge
jvm_memory_total_max 3.999268864E9
# TYPE jvm_memory_total_used gauge
jvm_memory_total_used 3.82665128E8
# TYPE jvm_thread_blocked_count gauge
jvm_thread_blocked_count 0.0
# TYPE jvm_thread_count gauge
jvm_thread_count 82.0
# TYPE jvm_thread_daemon_count gauge
jvm_thread_daemon_count 11.0
# TYPE jvm_thread_deadlock_count gauge
jvm_thread_deadlock_count 0.0
# TYPE jvm_thread_new_count gauge
jvm_thread_new_count 0.0
# TYPE jvm_thread_runnable_count gauge
jvm_thread_runnable_count 25.0
# TYPE jvm_thread_terminated_count gauge
jvm_thread_terminated_count 0.0
# TYPE jvm_thread_timed_waiting_count gauge
jvm_thread_timed_waiting_count 10.0
# TYPE jvm_thread_waiting_count gauge
jvm_thread_waiting_count 47.0
```