FROM openjdk:11

ENV HOME /root

RUN mkdir -p /root/otoroshi
RUN mkdir -p /root/coder

WORKDIR $HOME

COPY . $HOME/

RUN apt-get update -y
RUN apt-get install -y git wget curl vim tmux openssh-server zsh ack tig tree apt-transport-https openssl build-essential
RUN git clone https://github.com/robbyrussell/oh-my-zsh.git
RUN curl -o- https://raw.githubusercontent.com/creationix/nvm/v0.35.1/install.sh | bash
RUN chmod -R 777 $HOME/.nvm 
RUN curl -sS https://dl.yarnpkg.com/debian/pubkey.gpg | apt-key add -
RUN echo "deb https://dl.yarnpkg.com/debian/ stable main" | tee /etc/apt/sources.list.d/yarn.list
RUN apt-get update&& apt-get install yarn -y
RUN wget --quiet https://github.com/sbt/sbt/releases/download/v1.3.3/sbt-1.3.3.zip && unzip $HOME/sbt-1.3.3.zip && rm -f $HOME/sbt-1.3.3.zip
RUN mv $HOME/tmux.conf $HOME/.tmux.conf && mv $HOME/zshrc $HOME/.zshrc
RUN export NVM_DIR="/root/.nvm" && . "$NVM_DIR/nvm.sh"
RUN cd /root/otoroshi && git init && git remote add origin https://github.com/MAIF/otoroshi.git
RUN cd /root/coder && wget --quiet https://github.com/cdr/code-server/releases/download/2.1665-vsc1.39.2/code-server2.1665-vsc1.39.2-linux-x86_64.tar.gz && tar -xf code-server2.1665-vsc1.39.2-linux-x86_64.tar.gz && mv code-server2.1665-vsc1.39.2-linux-x86_64/code-server ./code-server && rm -rf code-server2.1665-vsc1.39.2-linux* 

EXPOSE 9999
EXPOSE 9998
EXPOSE 9997
EXPOSE 3040

CMD ["/root/entrypoint.sh"]