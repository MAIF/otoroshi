#!/bin/zsh

cd /root/otoroshi

echo "Fetching last sources"
if [! -d "./.git"]
then
  git init 
  git remote add origin https://github.com/MAIF/otoroshi.git
fi
git pull --rebase origin master
echo "Installing node"
export NVM_DIR="/root/.nvm" 
. "$NVM_DIR/nvm.sh" 
nvm install 8.6.0 
nvm use 8.6.0 
nvm alias default 8.6.0
cd /root/otoroshi/otoroshi/javascript
echo "installing nodejs dependencies"
yarn install
echo "starting tmux session"

LOCATION="/root/otoroshi"
SESSION_NAME="otoroshi-dev"

tmux start-server;
cd $LOCATION

tmux new-session -d -s $SESSION_NAME

# Create other windows.
tmux new-window -c $LOCATION/otoroshi -t $SESSION_NAME:1 -n server-build
tmux new-window -c $LOCATION/otoroshi -t $SESSION_NAME:2 -n tig
tmux new-window -c $LOCATION          -t $SESSION_NAME:3 -n coder

# Window "server-build"
tmux split-window -h -c $LOCATION/otoroshi/javascript -t $SESSION_NAME:1
tmux split-window -v -c $LOCATION/otoroshi -t $SESSION_NAME:1.2 

tmux send-keys -t $SESSION_NAME:1.1 "cd /root/otoroshi/otoroshi" C-m
tmux send-keys -t $SESSION_NAME:1.1 clear C-m
tmux send-keys -t $SESSION_NAME:1.2 "cd /root/otoroshi/javascript" C-m
tmux send-keys -t $SESSION_NAME:1.2 clear C-m
tmux send-keys -t $SESSION_NAME:1.2 "cd /root/otoroshi" C-m
tmux send-keys -t $SESSION_NAME:1.3 clear C-m

tmux send-keys -t $SESSION_NAME:1.1 "sbt '~run -Dapp.storage=file -Dapp.liveJs=true -Dhttps.port=9998 -Dotoroshi.scripts.enabled=true -Dapp.privateapps.port=9999 -Dapp.adminPassword=password -Dapp.domain=oto.tools -Dplay.server.https.engineProvider=ssl.DynamicSSLEngineProvider'" C-m
tmux send-keys -t $SESSION_NAME:1.2 "yarn start" C-m

# Window "tig"
tmux send-keys -t $SESSION_NAME:2 "tig" C-m

# Window "coder"
tmux send-keys -t $SESSION_NAME:3 "cd /root/coder" C-m
tmux send-keys -t $SESSION_NAME:3 "./code-server --disable-telemetry --port=9997 --auth=none" C-m

tmux select-window -t $SESSION_NAME:1
tmux select-pane -t $SESSION_NAME:1.1

tmux -u attach-session -t $SESSION_NAME



