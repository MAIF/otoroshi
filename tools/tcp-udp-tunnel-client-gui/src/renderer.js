const { ipcRenderer } = require('electron')

let currentId = '';

const sessionName = document.getElementById('sessionName');
const ok = document.getElementById('ok');
const text = document.getElementById('text');

ipcRenderer.on('set-id', (event, id) => {
  console.log(id);
  currentId = id;
  sessionName.textContent = id;
});

ok.addEventListener('click', (e) => {
  const value = text.value;
  ipcRenderer.send('session-value', { id: currentId, value });
});

