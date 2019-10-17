const { ipcRenderer } = require('electron')

let currentId = '';

ipcRenderer.on('set-id', (event, id) => {
  console.log(id);
  currentId = id;
});

const ok = document.getElementById('ok');
const text = document.getElementById('text');

ok.addEventListener('click', (e) => {
  const value = text.value;
  ipcRenderer.send('session-value', { id: currentId, value });
});

