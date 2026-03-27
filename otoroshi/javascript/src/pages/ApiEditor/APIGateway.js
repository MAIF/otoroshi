import React, { useEffect, useState } from 'react';
import { NgForm } from '../../components/nginputs';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import InfoCollapse from '../../components/InfoCollapse';
import { Row } from '../../components/Row';
import { useDraftOfAPI } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { MAX_WIDTH } from './constants';

export function APIGateway(props) {
  const { item, updateItem } = useDraftOfAPI();

  const [state, setState] = useState();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  useEffect(() => {
    if (item) {
      setState({
        domain: item.domain,
        contextPath: item.contextPath,
      });
    }
  }, [item]);

  const schema = {
    explanation: {
      renderer: () => {
        return (
          <InfoCollapse title="How domain and context path work" defaultOpen={true}>
            <p>
              The <strong>domain</strong> is the host through which your API will be exposed (e.g.{' '}
              <code>api.oto.tools</code>). It defines the entry point that clients will use to reach
              your API. You can use any domain that resolves to your Otoroshi instance.
            </p>
            <p>
              The <strong>context path</strong> is a path prefix appended after the domain to scope
              and version your API (e.g. <code>/v1</code>, <code>/v2</code>). All routes defined
              within this API will be served under this base path.
            </p>
            <p>
              Together, they form the full base URL of your API:{' '}
              <code>https://api.oto.tools/v1</code>. This means an endpoint defined as{' '}
              <code>/users</code> would be accessible at <code>https://api.oto.tools/v1/users</code>
              .
            </p>
          </InfoCollapse>
        );
      },
    },
    domain: {
      type: 'string',
      label: 'API Domain',
      placeholder: 'api.oto.tools',
    },
    contextPath: {
      type: 'string',
      label: 'Context path',
      placeholder: '/v1, /v2, etc',
    },
    merge: {
      renderer: (props) => {
        const isDefined = !!props.rootValue?.domain;
        return (
          <Row title="Complete API URL">
            <p>
              {isDefined
                ? `http://${props.rootValue?.domain ?? ''}${props.rootValue?.contextPath ?? ''}`
                : 'API URL will be displayed when the domain is provided.'}
            </p>
            <p
              className="m-0"
              style={{
                fontStyle: 'italic',
              }}
            >
              This URL is used to expose your API endpoints
            </p>
          </Row>
        );
      },
    },
  };

  return (
    <>
      <PageTitle title="API Gateway" {...props}>
        <DraftOnly>
          <FeedbackButton
            type="success"
            className="d-flex ms-auto"
            onPress={() =>
              updateItem({
                ...item,
                ...state,
              })
            }
            text={
              <div className="d-flex align-items-center">
                Update <VersionBadge size="xs" />
              </div>
            }
          />
        </DraftOnly>
      </PageTitle>

      <div className="actions-page mt-3" style={{ maxWidth: MAX_WIDTH }}>
        <NgForm value={state} onChange={setState} schema={schema} />
      </div>
    </>
  );
}
