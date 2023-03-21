
extern "C" {
  fn proxy_log(logLevel: u32, message: u64, size: u64) -> u32;
  fn proxy_log_event(context: u64, size: u64) -> u32;
  fn proxy_http_call(context: u64, size: u64) -> u32;
  fn get_proxy_state(context: u64) -> u32;
  fn get_proxy_state_value(context: u64, size: u64) -> u32;
  fn get_cluster_state(context: u64) -> u32;
  fn get_cluster_state_value(context: u64, size: u64) -> u32;
  fn proxy_datastore_keys(context: u64, size: u64) -> u32;
  fn proxy_datastore_get(context: u64, size: u64) -> u32;
  fn proxy_datastore_exists(context: u64, size: u64) -> u32;
  fn proxy_datastore_pttl(context: u64, size: u64) -> u32;
  fn proxy_datastore_setnx(context: u64, size: u64) -> u32;
  fn proxy_datastore_del(context: u64, size: u64) -> u32;
  fn proxy_datastore_incrby(context: u64, size: u64) -> u32;
  fn proxy_datastore_pexpire(context: u64, size: u64) -> u32;
  fn proxy_datastore_all_matching(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_keys(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_get(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_exists(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_pttl(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_setnx(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_del(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_incrby(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_pexpire(context: u64, size: u64) -> u32;
  fn proxy_plugin_datastore_all_matching(context: u64, size: u64) -> u32;
  fn proxy_plugin_map_set(context: u64, size: u64) -> u32;
  fn proxy_plugin_map_get(context: u64, size: u64) -> u32;
  fn proxy_plugin_map(unused: u32) -> u32;
  fn proxy_global_map_set(context: u64, size: u64) -> u32;
  fn proxy_global_map_get(context: u64, size: u64) -> u32;
  fn proxy_global_map(unused: u32) -> u32;
}

pub unsafe fn otoroshi_proxy_log(level: u32, msg: String) -> u32 {
  let mut mem = extism_pdk::Memory::new(msg.len());
  mem.store(msg);
  unsafe { proxy_log(level, mem.offset, mem.length) }
}