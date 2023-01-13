import JSZip from "jszip"
import Pako from "pako"

const f = (url, init = {}) => {
  return fetch(`/api${url}`, {
    ...init,
    credentials: 'include',
    headers: {
      ...init.headers || {},
      'Content-Type': 'application/json',
      Accept: 'application/json'
    },
  })
}

const rawFetch = (url, init = {}) => {
  return fetch(`/api${url}`, {
    ...init,
    credentials: 'include',
    headers: {
      ...init.headers || {}
    },
  })
}

const jsonFetch = (url, init) => f(url, init).then(r => r.json())

export const createPlugin = plugin => {
  return jsonFetch('/plugins', {
    method: 'POST',
    body: JSON.stringify({
      plugin
    })
  })
}

export const getPlugins = () => jsonFetch('/plugins')

export const getPlugin = plugin => rawFetch(`/plugins/${plugin}`)

export const getPluginConfig = plugin => jsonFetch(`/plugins/${plugin}/configurations`)

export const getPluginTemplate = type => f(`/templates?type=${type}`)
  .then(r => r.blob())

const buildZip = plugin => {
  const jsZip = new JSZip()

  plugin.files.map(file => {
    jsZip.file(`${file.filename}`, Pako.deflateRaw(file.content, { to: 'string' }));
  })

  return jsZip.generateAsync({ type: "uint8array" });
}

export const savePlugin = async plugin => {
  const bytes = await buildZip(plugin)

  return rawFetch(`/plugins/${plugin.pluginId}`, {
    method: 'PUT',
    body: bytes,
    headers: {
      'Content-Type': 'application/octet-stream'
    }
  })
}

export const buildPlugin = async plugin => {
  const bytes = await buildZip(plugin)

  return rawFetch(`/plugins/${plugin.pluginId}/build`, {
    method: 'POST',
    body: bytes,
    headers: {
      'Content-Type': 'application/octet-stream'
    }
  })
}

export const removePlugin = plugin => rawFetch(`/plugins/${plugin}`, {
  method: 'DELETE'
})