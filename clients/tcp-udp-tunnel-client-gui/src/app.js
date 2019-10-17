const { app, Menu, Tray, shell } = require('electron');
const path = require('path');
const fs = require('fs-extra');
const Client = require('./client');

let interval = 0;
let tray = null;
let currentProfile = null;
let connections = 0;
let connectionsPerTunnel = {};

const logoGrey = path.join(__dirname, '/otoroshi-logo-small-grey.png');
const logo = path.join(__dirname, '/otoroshi-logo-small.png');

function updateConnections(name, connection) {
  connections = 0;
  connectionsPerTunnel[name] = connection;
  Object.keys(connectionsPerTunnel).map(k => {
    connections = connections + connectionsPerTunnel[k];
  });
  scanProfilesAndUpdateTray();
}

function stopCurrentProfile() {
  if (currentProfile) currentProfile.stop();
  currentProfile = null;
  connections = 0;
  connectionsPerTunnel = {};
  scanProfilesAndUpdateTray();
}

function startProfile(p) {
  stopCurrentProfile();
  connections = 0;
  connectionsPerTunnel = {};
  p.run(updateConnections);
  currentProfile = p;
  scanProfilesAndUpdateTray();
}

function updateTray(profiles) {
  const running = !!currentProfile;
  const items = [
    { label: `Otoroshi tunneling client - ${running ? 'active' : 'inactive'}`, type: 'normal' },
  ];
  if (running) {
    items.push({ label: `${connections} active connections`, type: 'normal' });
    items.push({ type: 'separator' });
    //items.push({ label: currentProfile.name, type: 'radio', checked: true });
    profiles.map(p => items.push({ label: p.name, type: 'radio', checked: p.name === currentProfile.name, click: e => {
      startProfile(p);
    }}));
    items.push({ type: 'separator' });
    items.push({ label: 'Stop current profile', type: 'normal', click: e => {
      stopCurrentProfile();
    } });
    items.push({ type: 'separator' });
    currentProfile.tunnels.map(tunnel => {
      const label = `${(tunnel.transport || 'tcp').toUpperCase()} - ${tunnel.name} ${tunnel.transport === 'udp' ? '' : '- ' + ((connectionsPerTunnel[tunnel.name] || '0') + ' conn.')}`;
      items.push({ label, type: 'normal', enabled: tunnel.enabled });
    });
  } else {
    items.push({ type: 'separator' })
    profiles.map(p => items.push({ label: p.name, type: 'normal', click: e => {
      startProfile(p);
    }}));
  }
  items.push({ type: 'separator' })
  items.push({ label: `Show profile files`, type: 'normal', click: e => shell.showItemInFolder(path.join(app.getPath('userData'), 'profiles')) })
  items.push({ type: 'separator' })
  items.push({ label: `Quit`, type: 'normal', click: e => app.quit() })
  const contextMenu = Menu.buildFromTemplate(items);
  tray.setToolTip(`Otoroshi tunneling client - ${running ? 'active' : 'inactive'}`);
  running ? tray.setTitle(' ' + currentProfile.name + ' - ' + connections + ' conn.') : tray.setTitle('');
  running ? tray.setImage(logo) : tray.setImage(logoGrey)
  tray.setContextMenu(contextMenu);
}

let lastProfilesCount = 0;

function scanProfilesAndUpdateTray(active = true) {
  const userDataPath = app.getPath('userData');
  const profilesPath = path.join(userDataPath, 'profiles');
  fs.mkdirpSync(profilesPath);
  const profiles = fs.readdirSync(profilesPath).filter(f => f.endsWith('.json')).map(file => new Profile(path.join(profilesPath, file)));
  if (active) {
    lastProfilesCount = profiles.length;
    updateTray(profiles);
  } else {
    if (profiles.length !== lastProfilesCount) {
      lastProfilesCount = profiles.length;
      updateTray(profiles);
    }
  }
  return profiles;
}

class Profile {

  constructor(path) {
    const contentRaw = fs.readFileSync(path).toString('utf8');
    this.content = JSON.parse(contentRaw);
    this.name = this.content.name
    this.apikeys = this.content.apikeys
    this.tunnels = this.content.tunnels
  }

  run = (f) => {
    if (!this.client) {
      this.client = Client.start(this.content, f);
    }
  };

  stop = () => {
    if (this.client) {
      this.client.stop();
      this.client = null;
    }
  };
}

app.on('ready', () => {
  app.dock.hide();
  tray = new Tray(logoGrey);
  updateTray([]);
  scanProfilesAndUpdateTray();

  interval = setInterval(() => {
    scanProfilesAndUpdateTray(false);
  }, 2000)

  app.on('quit', () => {
    clearInterval(interval);
    if (currentProfile) {
      currentProfile.stop();
    }
  });

  app.on('window-all-closed', e => {

  });
});
