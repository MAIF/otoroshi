import JSZip from "jszip"
import Pako from "pako"

const f = (url, init = {}) => {
  return fetch(`${url}`, {
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
  return fetch(`${url}`, {
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

export const getPluginTemplate = type => f(`/templates?type=${type}`)
  .then(r => r.blob())

export const savePlugin = async plugin => {

  const jsZip = new JSZip()

  plugin.files.map(file => {
    jsZip.file(`${file.filename}`, Pako.deflateRaw(file.content, { to: 'string' }));
  })

  const bytes = await jsZip.generateAsync({ type: "uint8array" });

  return rawFetch(`/plugins/${plugin.filename}`, {
    method: 'PUT',
    body: bytes,
    headers: {
      'Content-Type': 'application/octet-stream'
    }
  })
}