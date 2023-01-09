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