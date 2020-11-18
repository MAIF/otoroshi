$(function() {

  function dec2hex (dec) {
    return dec < 10
      ? '0' + String(dec)
      : dec.toString(16)
  }
  
  function generateSecret(len) {
    var arr = new Uint8Array((len || 40) / 2)
    window.crypto.getRandomValues(arr)
    return Array.from(arr, dec2hex).join('')
  }

  var names = {
    'app_domain': "Domain name",
    'app_storage': "Otoroshi storage",
    'init_login': "User login",
    'init_pwd': "User password"
  };

  var orgaId = generateSecret(16);

  var state = {
    'admin_api_client_id': generateSecret(32),
    'admin_api_client_secret': generateSecret(32),
    'admin_api_group': generateSecret(32),
    'admin_api_service_id': generateSecret(32),
    'shared_key': generateSecret(32),
    'crypto_secret': generateSecret(32),
    'session_name': `oto-session-${generateSecret(8)}`,
    'app_domain': 'cleverapps.io',
    'app_storage': 'inmemory',
    'app_scheme': 'https',
    'app_backoffice_subdomain': `otoroshi-${orgaId}`,
    'app_api_subdomain': `otoroshi-api-${orgaId}`,
    'app_api_int_subdomain': `otoroshi-api-int-${orgaId}`,
    'app_papps_subdomain': `otoroshi-papps-${orgaId}`,
    'init_login': `${orgaId}@otoroshi.io`,
    'init_pwd': generateSecret(32),
    'version': '1.4.22',
  };

  fetch('https://updates.otoroshi.io/api/versions/latest', {
    method: 'GET',
    cors: true
  }).then(r => r.json()).then(r => {
    state.version = r.version_raw;
    render();
  });

  function render() {
    var template = `
      ${Object.keys(names).map(name => `${names[name]} <input type="text" name="${name}" value="${state[name]}"></input>`).join('\n')}
      <div style="display: flex; justify-content: center; width: 100%;">
      <button type="button" class="button" id="refresh">Refresh !</button>
      </div>
<div>
Your otoroshi instance will be located at <br/><br/>

<a href="https://${state.app_backoffice_subdomain}.${state.app_domain}" target="_blank">https://${state.app_backoffice_subdomain}.${state.app_domain}</a><br/><br/>

You can connect on your instance with the following credentials<br/><br/>

<code>${state.init_login} / ${state.init_pwd}</code><br/><br/>

You can use your instance api at<br/><br/>

<code>https://${state.app_api_subdomain}.${state.app_domain}</code><br/><br/>

with the following authorization header<br/><br/>

<code>Authorization: Basic ${btoa(`${state.admin_api_client_id}:${state.admin_api_client_secret}`)}</code><br/><br/>
</div>

copy the following environment variables in you clevercloud app in expert mode :<br/><br/>
      <pre>
<code>ADMIN_API_CLIENT_ID=${state.admin_api_client_id}
ADMIN_API_CLIENT_SECRET=${state.admin_api_client_secret}
ADMIN_API_GROUP=${state.admin_api_group}
ADMIN_API_SERVICE_ID=${state.admin_api_service_id}
CLAIM_SHAREDKEY=${state.shared_key}
OTOROSHI_INITIAL_ADMIN_LOGIN=${state.init_login}
OTOROSHI_INITIAL_ADMIN_PASSWORD=${state.init_pwd}
PLAY_CRYPTO_SECRET=${state.crypto_secret}
SESSION_NAME=${state.session_name}
APP_DOMAIN=${state.app_domain}
APP_BACKOFFICE_SUBDOMAIN=${state.app_backoffice_subdomain}
APP_PRIVATEAPPS_SUBDOMAIN=${state.app_papps_subdomain}
ADMIN_API_TARGET_SUBDOMAIN=${state.app_api_int_subdomain}
ADMIN_API_EXPOSED_SUBDOMAIN=${state.app_api_subdomain}
APP_ENV=prod
APP_STORAGE=${state.app_storage}
APP_ROOT_SCHEME=https
CC_OTO_VERSION=curl https://updates.otoroshi.io/api/versions/latest | jq -r '.version_raw'; 
CC_PRE_BUILD_HOOK=curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/${state.version}/otoroshi.jar'
CC_JAR_PATH=./otoroshi.jar
CC_JAVA_VERSION=11
PORT=8080
SESSION_DOMAIN=.${state.app_domain}
SESSION_MAX_AGE=604800000
SESSION_SECURE_ONLY=true
USER_AGENT=otoroshi-${orgaId}
MAX_EVENTS_SIZE=1
WEBHOOK_SIZE=100
APP_BACKOFFICE_SESSION_EXP=86400000
APP_PRIVATEAPPS_SESSION_EXP=86400000
ENABLE_METRICS=true
OTOROSHI_ANALYTICS_PRESSURE_ENABLED=true
USE_CACHE=true</code></pre>
</div>`
    var int = $('#clevercloud-envvars-internals');
    if (int.length) {
      $('#clevercloud-envvars-internals').html(template);
    } else {
      $('#clevercloud-envvars').html('<div id="clevercloud-envvars-internals">' + template + '</div>');
    }
  }


  function hook() {
    if ($('#clevercloud-envvars').length) {
      render();

      function fire(e) {
        var name = e.target.name;
        var value = e.target.value;
        state[name] = value;
        render();
      }

      $("#clevercloud-envvars").on('change', "input[type='text']", fire);
      $("#clevercloud-envvars input[type='text']").change(fire);
      $("#clevercloud-envvars").on('click', '#refresh', function() {
        render();
      });
    } else {
      console.log('not found !')
    }
  }

  hook()
});