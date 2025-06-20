import React, { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { LabelAndInput, NgBoxBooleanRenderer, NgForm } from '../../components/nginputs';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory, useLocation } from 'react-router-dom';
import { FeedbackButton } from './FeedbackButton';
import { RouteForm } from './form';
import { Button } from '../../components/Button';
import { ENTITIES, FormSelector } from '../../components/FormSelector';
import { DraftStateDaemon } from '../../components/Drafts/DraftEditor';
import {
  draftVersionSignal,
  updateEntityURLSignal,
} from '../../components/Drafts/DraftEditorSignal';
import { useSignalValue } from 'signals-react-safe';

const capitalize = 'Route';
const lowercase = 'route';
const fetchName = 'ROUTES';
const link = 'routes';
const entityName = 'route';

function SaveButton({ saveRoute, isCreation, entityName }) {
  const draftContext = useSignalValue(draftVersionSignal);

  if (draftContext.version === 'draft') return null;

  return (
    <FeedbackButton
      type="success"
      className="ms-2 mb-1"
      onPress={saveRoute}
      text={isCreation ? `Create ${entityName}` : `Save`}
    />
  );
}

export const Informations = forwardRef(
  ({ isCreation, value, setValue, setSaveButton, routeId, ...props }, ref) => {
    const history = useHistory();
    const location = useLocation();
    const valueRef = useRef();

    useEffect(() => {
      valueRef.current = value;
    }, [value]);

    const [showAdvancedForm, toggleAdvancedForm] = useState(false);

    useImperativeHandle(ref, () => ({
      onTestingButtonClick() {
        history.push(`/${link}/${value.id}?tab=flow`, { showTryIt: true });
      },
    }));

    useEffect(() => {
      setSaveButton(
        <SaveButton saveRoute={saveRoute} isCreation={isCreation} entityName={entityName} />
      );
    }, [value]);

    const saveRoute = () => {
      if (isCreation || location.state?.routeFromService) {
        return nextClient
          .forEntityNext(nextClient.ENTITIES[fetchName])
          .create(value)
          .then(() => history.push(`/${link}/${value.id}?tab=flow`));
      } else {
        return nextClient
          .forEntityNext(nextClient.ENTITIES[fetchName])
          .update(valueRef.current)
          .then((res) => {
            window.location.reload();
          });
      }
    };

    const schema = {
      id: {
        type: 'string',
        visible: false,
      },
      name: {
        type: 'string',
        label: `${capitalize} name`,
        placeholder: `Your ${lowercase} name`,
        help: `The name of your ${lowercase}. Only for debug and human readability purposes.`,
        // constraints: [constraints.required()],
      },
      enabled: {
        renderer: (props) => {
          return (
            <>
              <div className="d-flex align-items-baseline">
                <p className="ms-2">Exposition of the route</p>
                <div className="d-flex flex-column gap-3 ms-3">
                  <span
                    className={`badge bg-${props.value ? 'success' : 'danger'}`}
                    style={{ width: 'fit-content' }}
                  >
                    {props.value ? 'Exposed' : 'Disabled'}
                  </span>
                  {props.value ? (
                    <Button
                      type="danger"
                      className="btn-sm mb-3"
                      text="Disable this route"
                      onClick={() => {
                        window
                          .newConfirm(
                            'Are you sure you disable this route ? Traffic will be stop immediately.'
                          )
                          .then((ok) => {
                            if (ok) {
                              valueRef.current = {
                                ...value,
                                enabled: false,
                              };
                              saveRoute().then(() => window.location.reload());
                            }
                          });
                      }}
                    />
                  ) : (
                    <Button
                      type="success"
                      className="btn-sm mb-3"
                      text="Publish this route"
                      onClick={() => {
                        valueRef.current = {
                          ...value,
                          enabled: true,
                        };
                        saveRoute().then(() => window.location.reload());
                      }}
                    />
                  )}
                </div>
              </div>
            </>
          );
        },
      },
      capture: {
        type: 'bool',
        label: 'Capture route traffic',
        props: {
          labelColumn: 3,
        },
      },
      debug_flow: {
        type: 'bool',
        label: 'Debug the route',
        props: {
          labelColumn: 3,
        },
      },
      export_reporting: {
        type: 'bool',
        label: 'Export reporting',
        props: {
          labelColumn: 3,
        },
      },
      description: {
        type: 'string',
        label: 'Description',
        placeholder: 'Your route description',
        help: 'The description of your route. Only for debug and human readability purposes.',
      },
      groups: {
        type: 'array-select',
        label: 'Groups',
        props: {
          optionsFrom: '/bo/api/proxy/api/groups',
          optionsTransformer: (arr) => arr.map((item) => ({ value: item.id, label: item.name })),
        },
      },
      bound_listeners: {
        type: 'array-select',
        label: 'Bound listeners',
        props: {
          optionsFrom: '/extensions/cloud-apim/extensions/http-listeners/all',
          optionsTransformer: (arr) =>
            arr.map((item) => ({ value: item.value, label: item.label })),
        },
      },
      core_metadata: {
        label: 'Metadata shortcuts',
        type: 'string',
        customRenderer: (props) => {
          const metadata = props.rootValue?.metadata || {};

          const CORE_BOOL_METADATA = [
            {
              key: 'otoroshi-core-user-facing',
              label: 'User Facing',
              description:
                'The fact that this service will be seen by users and cannot be impacted by the Snow Monkey',
            },
            {
              key: 'otoroshi-core-use-akka-http-client',
              label: 'Use Akka Http Client',
              description: 'Use akka http client for this service',
            },
            {
              key: 'otoroshi-core-use-netty-http-client',
              label: 'Use Netty Client',
              description: 'Use netty http client for this service',
            },
            {
              key: 'otoroshi-core-use-akka-http-ws-client',
              label: 'Use Akka Http Ws Client',
              description: 'Use akka http client for this service on websocket calls',
            },
            {
              key: 'otoroshi-core-issue-lets-encrypt-certificate',
              label: `Issue a Let's Encrypt Certificate`,
              description: `Flag to automatically issue a Let's Encrypt cert for this service`,
            },
            {
              key: 'otoroshi-core-issue-certificate',
              label: 'Issue a Certificate',
              description: 'Flag to automatically issue a cert for this service',
            },
          ];

          const CORE_STRING_METADATA = [
            {
              key: 'otoroshi-core-issue-certificate-ca',
              label: 'Issue Certificate CA',
              description: 'CA for cert issuance',
            },
            {
              key: 'otoroshi-core-openapi-url',
              label: 'OPENAPI URL',
              description:
                'Represent if a service exposes an API with an optional url to an openapi descriptor',
            },
          ];

          return (
            <LabelAndInput label="Metadata shortcuts">
              <div className="d-flex flex-wrap align-items-stretch" style={{ gap: 6 }}>
                {CORE_BOOL_METADATA.map(({ key, label, description }) => {
                  return (
                    <div style={{ flex: 1, minWidth: '40%' }}>
                      <NgBoxBooleanRenderer
                        rawDisplay
                        description={description}
                        label={label}
                        value={metadata[key]}
                        onChange={(e) => {
                          if (e) {
                            setValue({
                              ...value,
                              metadata: {
                                ...(metadata || {}),
                                [key]: '' + e,
                              },
                            });
                          } else {
                            setValue({
                              ...value,
                              metadata: Object.fromEntries(
                                Object.entries({ ...(metadata || {}) }).filter((f) => f[0] !== key)
                              ),
                            });
                          }
                        }}
                      />
                    </div>
                  );
                })}
                {CORE_STRING_METADATA.map(({ key, label, description }) => {
                  return (
                    <div style={{ flex: 1, minWidth: '40%' }}>
                      <NgBoxBooleanRenderer
                        rawDisplay
                        description={description}
                        label={label}
                        value={metadata[key]}
                        onChange={(e) => {
                          if (e) {
                            setValue({
                              ...value,
                              metadata: {
                                ...(metadata || {}),
                                [key]: 'ENTER YOUR VALUE',
                              },
                            });
                          } else {
                            setValue({
                              ...value,
                              metadata: Object.fromEntries(
                                Object.entries({ ...(metadata || {}) }).filter((f) => f[0] !== key)
                              ),
                            });
                          }
                        }}
                      />
                    </div>
                  );
                })}
              </div>
            </LabelAndInput>
          );
        },
      },
      metadata: {
        type: 'object',
        label: 'Metadata',
      },
      tags: {
        type: 'string',
        array: true,
        label: 'Tags',
      },
      _loc: {
        type: 'location',
        props: {
          label: 'Location',
        },
      },
      danger_zone: {
        renderer: () => {
          const what = window.location.pathname.split('/')[3];
          const id = window.location.pathname.split('/')[4];
          const kind = nextClient.ENTITIES.ROUTES;
          return (
            <div className="row mb-3">
              <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>
                Delete this route
              </label>
              <div className="col-sm-10">
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  <p>Once you delete a route, there is no going back. Please be certain.</p>
                  <Button
                    style={{ width: 'fit-content' }}
                    disabled={id === props.globalEnv.adminApiId}
                    type="danger"
                    onClick={() => {
                      window
                        .newConfirm('Are you sure you want to delete this entity ?')
                        .then((ok) => {
                          if (ok) {
                            nextClient.deleteById(kind, id).then(() => {
                              history.push('/' + what);
                            });
                          }
                        });
                    }}
                  >
                    Delete this route
                  </Button>
                </div>
              </div>
            </div>
          );
        },
      },
    };

    const flow = [
      {
        type: 'group',
        name: 'Expose your route',
        fields: ['enabled'],
      },
      '_loc',
      {
        type: 'group',
        name: 'Route',
        fields: [
          'name',
          'description',
          'groups',
          {
            type: 'grid',
            name: 'Flags',
            fields: ['debug_flow', 'export_reporting', 'capture'],
          },
        ],
      },
      {
        type: 'group',
        name: 'Misc.',
        collapsed: true,
        fields: ['bound_listeners', 'tags', 'metadata', 'core_metadata'],
      },
      {
        type: 'group',
        name: 'Danger zone',
        collapsed: true,
        fields: ['danger_zone'],
      },
    ];

    return (
      <>
        <DraftStateDaemon
          value={value}
          setValue={(newValue) => {
            setValue(newValue);
            valueRef.current = newValue;
          }}
          updateEntityURL={() => {
            updateEntityURLSignal.value = saveRoute;
          }}
        />

        {showAdvancedForm ? (
          <RouteForm
            routeId={routeId}
            setValue={setValue}
            value={value}
            history={history}
            location={location}
            isCreation={isCreation}
          />
        ) : (
          <NgForm
            schema={schema}
            flow={flow}
            value={value}
            onChange={(v) => {
              setValue(v);
            }}
          />
        )}

        <div className="d-flex align-items-center justify-content-end mt-3 p-0">
          <FormSelector onChange={toggleAdvancedForm} entity={ENTITIES.ROUTES} className="me-1" />
          <Button
            type="danger"
            className="btn-sm"
            onClick={() => history.push(`/${link}`)}
            text="Cancel"
          />
        </div>
      </>
    );
  }
);
