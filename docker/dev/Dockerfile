FROM openjdk:8

LABEL maintainer "MAIF <oss@maif.fr>"

ENV HOME /root

WORKDIR $HOME

COPY . $HOME/

RUN apt-get update -y
RUN apt-get install -y git wget curl vim tmux openssh-server zsh ack tig tree apt-transport-https openssl
RUN git clone https://github.com/robbyrussell/oh-my-zsh.git
RUN curl -o- https://raw.githubusercontent.com/creationix/nvm/v0.33.4/install.sh | bash
RUN chmod -R 777 $HOME/.nvm 
RUN curl -sS https://dl.yarnpkg.com/debian/pubkey.gpg | apt-key add -
RUN echo "deb https://dl.yarnpkg.com/debian/ stable main" | tee /etc/apt/sources.list.d/yarn.list
RUN apt-get update
RUN apt-get install yarn -y
RUN curl https://sh.rustup.rs > $HOME/install-rustup.sh
RUN chmod +x $HOME/install-rustup.sh
RUN $HOME/install-rustup.sh -y
RUN $HOME/.cargo/bin/rustup update
RUN $HOME/.cargo/bin/rustup default stable
RUN $HOME/.cargo/bin/cargo install cargo-watch
RUN $HOME/.cargo/bin/rustup component add rustfmt-preview --toolchain=nightly
RUN wget --quiet https://github.com/sbt/sbt/releases/download/v1.1.0/sbt-1.1.0.zip
RUN unzip $HOME/sbt-1.1.0.zip
RUN mkdir -p $HOME/.sbt/1.0/plugins
RUN touch $HOME/.sbt/1.0/plugins/build.sbt
RUN echo 'addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.1")' >> $HOME/.sbt/1.0/plugins/build.sbt
RUN curl -fLo $HOME/.vim/autoload/plug.vim --create-dirs https://raw.githubusercontent.com/junegunn/vim-plug/master/plug.vim
RUN mv $HOME/tmux.conf $HOME/.tmux.conf
RUN mv $HOME/vimrc $HOME/.vimrc
RUN mv $HOME/zshrc $HOME/.zshrc
RUN vim -c ":PlugInstall" -c ":qa"
RUN export NVM_DIR="/root/.nvm" && . "$NVM_DIR/nvm.sh" && nvm install 8.6.0 && nvm use 8.6.0 && nvm alias default 8.6.0
RUN git clone https://github.com/MAIF/otoroshi.git $HOME/otoroshi
RUN rm -f $HOME/Docker
RUN rm -f $HOME/install-rustup.sh

EXPOSE 8080
EXPOSE 9999
EXPOSE 22

CMD [""]
# docker run -p "8080:8080" -p "9999:9999" -it otoroshi-dev zsh
# docker run -it otoroshi-dev zsh