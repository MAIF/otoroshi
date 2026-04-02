import React, { useEffect } from 'react';
import { v4 } from 'uuid';
import { NgForm } from '../../components/nginputs';
import { Row } from '../../components/Row';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI } from './hooks';
import { MAX_WIDTH } from './constants';
import { RoutesView } from './Dashboard';
import PageTitle from '../../components/PageTitle';

function CopyableInput({ value, onRotate }) {
  const [copied, setCopied] = React.useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(value);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="d-flex">
      <input
        className="form-control"
        readOnly
        type="text"
        value={value}
        style={{
          borderTopRightRadius: 0,
          borderBottomRightRadius: 0,
          borderRight: 'none',
        }}
      />
      <button
        className={`btn ${copied ? 'btn-success' : 'btn-sm btn-secondary'}`}
        style={{ borderTopLeftRadius: 0, borderBottomLeftRadius: 0, minWidth: 40 }}
        title={copied ? 'Copied!' : 'Copy to clipboard'}
        onClick={handleCopy}
      >
        <i className={`fas ${copied ? 'fa-check' : 'fa-copy'}`} />
      </button>
      {onRotate && (
        <button
          className="btn btn-sm btn-secondary ms-1"
          title="Rotate value"
          onClick={onRotate}
        >
          <i className="fas fa-rotate" />
        </button>
      )}
    </div>
  );
}

function TestingConfiguration(props) {
  const headerKey = props.rootValue?.headerKey;
  const headerValue = props.rootValue?.headerValue;

  return (
    <>
      <Row title="">
        <div
          className="d-flex flex-column gap-3 p-3 rounded"
          style={{ background: 'var(--bg-color_level2, #1a1a2e)', border: '1px solid var(--color-primary, #f9b000)', maxWidth: 700 }}
        >
          <div className="d-flex align-items-center gap-2" style={{ color: 'var(--color-primary, #f9b000)', fontWeight: 600 }}>
            <i className="fas fa-flask me-1" />
            How to call your draft routes
          </div>
          <ol className="mb-0 ps-3" style={{ lineHeight: '2rem' }}>
            <li>Make sure <strong>Testing is enabled</strong> above</li>
            <li>
              Copy the <strong>header name</strong> below and add it to your request
            </li>
            <li>
              Copy the <strong>header value</strong> below and use it as the header value
            </li>
          </ol>
          <div
            className="p-2 rounded"
            style={{ background: 'var(--bg-color_level3, #111)', fontFamily: 'monospace', fontSize: 13, wordBreak: 'break-all' }}
          >
            <span style={{ color: '#888' }}>curl</span>{' '}
            <span style={{ color: '#f9b000' }}>-H</span>{' '}
            <span style={{ color: '#a3e635' }}>
              "{headerKey}: {headerValue}"
            </span>{' '}
            <span style={{ color: '#888' }}>https://your-api-host/...</span>
          </div>
        </div>
      </Row>
      <Row title="Header name">
        <CopyableInput value={headerKey} />
      </Row>
      <Row title="Header value">
        <CopyableInput
          value={headerValue}
          onRotate={() =>
            props.onSecretRotation({
              ...props.item.testing,
              headerValue: v4(),
            })
          }
        />
      </Row>
    </>
  );
}

export function Testing(props) {
  const { item, version, updateItem } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle(undefined);
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

  let flow = ['enabled'];

  if (item.testing.enabled) flow = [...flow, 'config', 'routes'];

  if (version === 'Published')
    return (
      <div className="alert alert-warning">
        Testing mode is only available in the draft version.
      </div>
    );

  return <div className='page'>
    <PageTitle title="Testing Mode" />

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
      flow={[{
        type: 'group',
        fields: flow,
        collapsable: false
      }]}
    />
  </div>
}
