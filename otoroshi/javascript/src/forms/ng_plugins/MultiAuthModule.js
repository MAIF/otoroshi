import React, { useEffect, useState } from 'react';
import CodeInput from '../../components/inputs/CodeInput';
import * as BackOfficeServices from '../../services/BackOfficeServices';

export default {
  id: 'cp:otoroshi.next.plugins.MultiAuthModule',
  config_schema: {
    auth_modules: {
      type: 'array-select',
      label: 'Auth. modules',
      props: {
        optionsFrom: '/bo/api/proxy/api/auths',
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      },
    },
    use_email_prompt: {
      type: 'box-bool',
      label: 'Use email prompt',
      props: {
        description: 'Use this flag and the following field to dispatch users to providers',
      },
    },
    users_groups: {
      renderer: (props) => {
        console.log(props);
        const [authConfigs, setAuthConfigs] = useState([]);

        useEffect(() => {
          BackOfficeServices.findAllAuthConfigs().then((r) => setAuthConfigs(r.data || []));
        }, []);

        if (!props.rootValue.use_email_prompt) return null;

        const authModules = props.rootValue.auth_modules || [];
        const users = props.value || authModules.reduce((acc, c) => ({ ...acc, [c]: [] }), {});

        return (
          <div>
            {authModules.map((auth) => {
              return (
                <CodeInput
                  label={`${authConfigs.find((f) => f.id === auth)?.name || auth} email users`}
                  mode="json"
                  value={JSON.stringify(users[auth], null, 2)}
                  onChange={(e) => {
                    if (e.trim() === '') {
                      props.onChange({ ...users, [auth]: [] });
                    } else {
                      props.onChange({ ...users, [auth]: JSON.parse(e) });
                    }
                  }}
                />
              );
            })}
          </div>
        );
      },
    },
    pass_with_apikey: {
      type: 'box-bool',
      label: 'Pass with apikey',
      props: {
        description: 'Authentication config can only be called with an API key',
      },
    },
  },
  config_flow: ['auth_modules', 'pass_with_apikey', 'use_email_prompt', 'users_groups'],
};
