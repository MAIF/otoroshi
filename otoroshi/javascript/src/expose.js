// the webpack build wrapped every entrypoint in a umd bundle that published the `Otoroshi`
// global. vite serves native es modules in dev, so the entrypoints publish it themselves.
// mutate in place so the umd wrapper of the prod build keeps filling the same object.
export function expose(api) {
  window.Otoroshi = window.Otoroshi || {};
  Object.assign(window.Otoroshi, api);
  return window.Otoroshi;
}
