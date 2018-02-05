#!/bin/bash

LOCATION=`pwd`
SESSION_NAME="otoroshi-dev"

tmux start-server;
cd $LOCATION

tmux new-session -d -s $SESSION_NAME

# Create other windows.
tmux new-window -c $LOCATION/otoroshi -t $SESSION_NAME:1 -n server-build
tmux new-window -c $LOCATION/otoroshi -t $SESSION_NAME:2 -n server-editor
tmux new-window -c $LOCATION/clients/cli -t $SESSION_NAME:3 -n cli-build
tmux new-window -c $LOCATION/clients/cli -t $SESSION_NAME:4 -n cli-editor
tmux new-window -c $LOCATION/clients/demo -t $SESSION_NAME:5 -n demo-env
tmux new-window -c $LOCATION -t $SESSION_NAME:6 -n all-files

# Window "server-editor"
tmux send-keys -t $SESSION_NAME:2 vim C-m

# Window "cli-editor"
tmux send-keys -t $SESSION_NAME:4 vim C-m

# Window "all-files"
tmux send-keys -t $SESSION_NAME:6 vim C-m

# Window "server-build"
tmux split-window -h -c $LOCATION/otoroshi/javascript -t $SESSION_NAME:1
tmux split-window -v -l 5 -c $LOCATION/otoroshi -t $SESSION_NAME:1.1 
tmux split-window -v -c $LOCATION/otoroshi -t $SESSION_NAME:1.3 -p 25
tmux split-window -v -l 5 -c $LOCATION/manual -t $SESSION_NAME:1.3

tmux send-keys -t $SESSION_NAME:1.1 "cd $LOCATION/otoroshi" C-m
tmux send-keys -t $SESSION_NAME:1.1 clear C-m
tmux send-keys -t $SESSION_NAME:1.2 clear C-m
tmux send-keys -t $SESSION_NAME:1.3 clear C-m
tmux send-keys -t $SESSION_NAME:1.3 "nvm use 8.6.0" C-m
tmux send-keys -t $SESSION_NAME:1.3 "yarn install" C-m
tmux send-keys -t $SESSION_NAME:1.5 clear C-m
tmux send-keys -t $SESSION_NAME:1.4 clear C-m

tmux send-keys -t $SESSION_NAME:1.1 "sbt"  C-m
tmux send-keys -t $SESSION_NAME:1.2 "./scripts/fmt.sh"
tmux send-keys -t $SESSION_NAME:1.3 "yarn start" C-m
tmux send-keys -t $SESSION_NAME:1.4 "sbt '~paradox'" C-m
tmux send-keys -t $SESSION_NAME:1.5 "git"

# Window "cli-build"
tmux split-window -h -c $LOCATION/clients/cli -t $SESSION_NAME:3

tmux send-keys -t $SESSION_NAME:3.1 clear C-m
tmux send-keys -t $SESSION_NAME:3.2 clear C-m

tmux send-keys -t $SESSION_NAME:3.1 "cargo watch -x build" C-m
tmux send-keys -t $SESSION_NAME:3.2 "./target/debug/otoroshicli -h" 

# Window "demo-env"
tmux split-window -h -c $LOCATION/clients/demo -t $SESSION_NAME:5
tmux split-window -v -c $LOCATION/clients/demo -t $SESSION_NAME:5.1 -p 25
tmux split-window -v -c $LOCATION/clients/demo -t $SESSION_NAME:5.1 -p 25
tmux split-window -v -c $LOCATION/clients/demo -t $SESSION_NAME:5.1 -p 25
tmux split-window -v -c $LOCATION/clients/demo -t $SESSION_NAME:5.5 -p 50

tmux send-keys -t $SESSION_NAME:5.1 "nvm use 8.6.0" C-m
tmux send-keys -t $SESSION_NAME:5.3 "nvm use 8.6.0" C-m
tmux send-keys -t $SESSION_NAME:5.4 "nvm use 8.6.0" C-m
tmux send-keys -t $SESSION_NAME:5.5 "nvm use 8.6.0" C-m
tmux send-keys -t $SESSION_NAME:5.2 "nvm use 8.6.0" C-m
tmux send-keys -t $SESSION_NAME:5.6 "nvm use 8.6.0" C-m

tmux send-keys -t $SESSION_NAME:5.1 clear C-m
tmux send-keys -t $SESSION_NAME:5.3 clear C-m
tmux send-keys -t $SESSION_NAME:5.4 clear C-m
tmux send-keys -t $SESSION_NAME:5.5 clear C-m
tmux send-keys -t $SESSION_NAME:5.2 clear C-m
tmux send-keys -t $SESSION_NAME:5.6 clear C-m

tmux send-keys -t $SESSION_NAME:5.1 "java -jar ../../otoroshi/target/scala-2.11/otoroshi.jar" 
tmux send-keys -t $SESSION_NAME:5.2 "node demo.js server --port 8081 --name 'server 1'" 
tmux send-keys -t $SESSION_NAME:5.3 "node demo.js server --port 8081 --name 'server 2'" 
tmux send-keys -t $SESSION_NAME:5.4 "node demo.js server --port 8081 --name 'server 3'" 
tmux send-keys -t $SESSION_NAME:5.5 "node demo.js injector" 
tmux send-keys -t $SESSION_NAME:5.6 "../cli/target/debug/otoroshicli" 

tmux select-window -t $SESSION_NAME:1
tmux select-pane -t $SESSION_NAME:1.1

tmux -u attach-session -t $SESSION_NAME
