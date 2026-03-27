import React, { useEffect } from 'react';
import { useHistory, useLocation } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import { NgForm } from '../../components/nginputs';
import { Button } from '../../components/Button';
import { Row } from '../../components/Row';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { MAX_WIDTH } from './constants';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';

export function Informations(props) {
  const history = useHistory();
  const location = useLocation();

  const { item, setItem, updateItem, isDraft } = useDraftOfAPI();

  const schema = {
    location: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'id', placeholder: '---' } },
    enabled: {
      type: 'box-bool',
      label: 'Enabled API',
      props: {
        description:
          'It determines whether the API is globally enabled or not. If you disable your API, neither the draft nor the production environment will be available. All calls will result in a `Routes not found` error.',
      },
    },
    name: {
      type: 'string',
      label: 'Name',
    },
    description: {
      type: 'string',
      label: 'Description',
    },
    version: {
      type: 'string',
      label: 'Version',
    },
    metadata: {
      type: 'object',
      label: 'Metadata',
    },
    tags: {
      type: 'array',
      label: 'Tags',
    },
    capture: {
      type: 'box-bool',
      label: 'Capture endpoint traffic',
      props: {
        description:
          'Emit a TrafficCaptureEvent for each request, including request and response bodies. It can be exported using Data Exporters.',
      },
    },
    owner: {
      label: 'Owner',
      type: 'form',
      schema: {
        ref: {
          label: 'Owner ref.',
          type: 'string',
        },
        config: {
          type: 'json',
          label: 'Owner ref. config.',
          props: {
            defaultValue: '{}',
            height: 100,
          },
        },
      },
      flow: ['ref', 'config'],
    },
    visibility: {
      label: 'Visibility',
      type: 'form',
      schema: {
        kind: {
          label: 'Kind',
          type: 'dots',
          props: {
            defaultValue: 'public',
            options: [
              { value: 'public', label: 'Public' },
              { value: 'semi_public', label: 'Semi Public' },
              { value: 'private', label: 'Private' },
              { value: 'custom', label: 'Custom' },
            ],
          },
        },
        config: {
          type: 'json',
          label: 'Visibility config.',
          props: {
            defaultValue: '{}',
            height: 100,
          },
        },
      },
      flow: ['kind', 'config'],
    },
    members: {
      array: true,
      label: 'Members',
      type: 'form',
      schema: {
        ref: {
          label: 'Owner ref.',
          type: 'string',
        },
        config: {
          type: 'json',
          label: 'Owner ref. config.',
          props: {
            defaultValue: '{}',
            height: 100,
          },
        },
      },
      flow: ['ref', 'config'],
    },
    hooks: {
      array: true,
      label: 'State hooks',
      type: 'form',
      schema: {
        ref: {
          label: 'Hook ref.',
          type: 'string',
        },
        config: {
          type: 'json',
          label: 'Hook config.',
          props: {
            defaultValue: '{}',
            height: 100,
          },
        },
      },
      flow: ['ref', 'config'],
    },
    debug_flow: {
      type: 'box-bool',
      label: 'Debug the endpoint',
      props: {},
    },
    export_reporting: {
      type: 'box-bool',
      label: 'Export reporting',
      props: {
        description:
          'Export execution of each steps of the route as RequestFlowReport event. It can be exported using Data Exporters. This feature can have actual impact on CPU and RAM consumption',
      },
    },
    danger_zone: {
      renderer: (inputProps) => {
        return (
          <Row title="Delete this API">
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              <p>Once you delete an API, there is no going back. Please be certain.</p>
              <Button
                style={{ width: 'fit-content' }}
                disabled={inputProps.rootValue?.id === props.globalEnv.adminApiId} // TODO
                type="danger"
                onClick={() => {
                  window.newConfirm('Are you sure you want to delete this entity ?').then((ok) => {
                    if (ok) {
                      nextClient
                        .forEntityNext(nextClient.ENTITIES.APIS)
                        .deleteById(inputProps.rootValue?.id)
                        .then(() => {
                          historyPush(history, location, '/');
                        });
                    }
                  });
                }}
              >
                Delete this API
              </Button>
            </div>
          </Row>
        );
      },
    },
  };
  const flow = [
    'location',
    {
      type: 'group',
      collapsable: false,
      name: 'API',
      fields: ['enabled', 'name', 'description', 'version'],
    },
    {
      type: 'group',
      collapsed: true,
      name: 'Informations',
      fields: ['owner', 'visibility', 'members', 'hooks'],
    },
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: ['tags', 'metadata', 'debug_flow', 'export_reporting', 'capture'],
    },
    isDraft
      ? {
          type: 'group',
          name: 'Danger zone',
          collapsed: true,
          fields: ['danger_zone'],
        }
      : null,
  ].filter((f) => f);

  const updateAPI = () => {
    updateItem().then(() => historyPush(history, location, `/apis/${item.id}`));
  };

  if (!item) return <SimpleLoader />;

  return (
    <>
      <PageTitle
        style={{
          paddingBottom: 0,
        }}
        title="Informations"
        {...props}
      >
        <FeedbackButton
          type="success"
          className="ms-2 mb-1 d-flex align-items-center"
          onPress={updateAPI}
          text={
            <>
              Update <VersionBadge size="xs" className="ms-2" />
            </>
          }
        />
      </PageTitle>
      <div style={{ maxWidth: MAX_WIDTH }}>
        <NgForm schema={schema} flow={flow} value={item} onChange={setItem} />
      </div>
    </>
  );
}
