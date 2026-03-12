import React, { useEffect } from 'react';
import { v4 } from 'uuid';
import { NgForm } from '../../components/nginputs';
import { Row } from '../../components/Row';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI } from './hooks';
import { MAX_WIDTH } from './constants';
import { RoutesView } from './Dashboard';

function TestingConfiguration(props) {
  return <>
    <Row title="Configuration">
      Enable testing on your API to allow you to call all enabled routes. You just need to pass the
      following specific header when making the calls. This security measure, enforced by Otoroshi,
      prevents unauthorized users from accessing your draft API.
    </Row>
    <Row title="Header">
      <input className="form-control" readOnly type="text" value={props.rootValue?.headerKey} />
    </Row>
    <Row title="Value">
      <div className='d-flex'>
        <input
          className="form-control"
          disabled
          type="text"
          value={props.rootValue?.headerValue}
          style={{
            borderTopRightRadius: 0,
            borderBottomRightRadius: 0,
            borderRight: 'none'
          }}
        />

        <button
          className="btn btn-primary"
          style={{
            borderTopLeftRadius: 0,
            borderBottomLeftRadius: 0
          }}
          title="copy bearer"
          onClick={() => {
            props.onSecretRotation({
              ...props.item.testing,
              headerValue: v4(),
            });
          }}
        >
          <i className="fas fa-rotate" />
        </button>
      </div>
    </Row>
  </>
}

export function Testing(props) {
  const { item, version, updateItem } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle('Testing mode');
  }, []);

  if (!item) return <SimpleLoader />;

  const schema = {
    enabled: {
      type: 'box-bool',
      label: 'Enabled',
      props: {
        description:
          'When enabled, this option allows draft routes to be exposed. These routes can be accessed using a specific header, ensuring they remain available only for testing purposes.',
      },
    },
    config: {
      renderer: (props) => (
        <TestingConfiguration
          {...props}
          item={item}
          onSecretRotation={(testing) => {
            updateItem({
              ...item,
              testing,
            });
          }}
        />
      ),
    },
    routes: {
      renderer: () => (
        <Row title="Endpoints">
          <div className="relative">
            <RoutesView api={item} />
          </div>
        </Row>
      ),
    },
  };

  let flow = ['enabled', 'config'];

  if (item.testing.enabled) flow = [...flow, 'routes'];

  if (version === 'Published')
    return (
      <div className="alert alert-warning">
        Testing mode is only available in the draft version.
      </div>
    );

  return (
    <>
      <div
        style={{
          maxWidth: MAX_WIDTH,
          margin: 'auto',
        }}
      >
        <NgForm
          value={item.testing}
          onChange={(testing) => {
            if (testing)
              updateItem({
                ...item,
                testing,
              });
          }}
          schema={schema}
          flow={flow}
        />
      </div>
    </>
  );
}
