# TCP tunnel client

## Test it

Define an otoroshi service on `http://foo.oto.tools:8080` that target your local ssh server at `http://127.0.0.1:22` (here http is irrelevant, it's just a UI issue) and enable the `Enable TCP tunneling` flag. Then either activate public access, apikey access or authenticated access. 

Run the local tunnel client with :

```sh
# test public access
yarn start -- --access_type=public --remote=http://http://foo.oto.tools:8080 --port=2222

# test apikey access
yarn start -- --access_type=apikey apikey=clientId:clientSecret --remote=http://http://foo.oto.tools:8080 --port=2222

# test session access
yarn start -- --access_type=session --remote=http://http://foo.oto.tools:8080 --port=2222
```

if you use the `session` access type, you'll have to login in your browser, copy the session token and paste it in your terminal.

Then try to access the service from your ssh client

```sh
ssh localuser@127.0.0.1 -p 2222
```