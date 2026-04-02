import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { nextClient, fetchWrapperNext } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { NgForm } from '../../components/nginputs';
import { Button } from '../../components/Button';
import PageTitle from '../../components/PageTitle';
import InfoCollapse from '../../components/InfoCollapse';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush, linkWithQuery } from './hooks';
import { VersionBadge } from './DraftOnly';
import { APIState } from './Dashboard';
import { MAX_WIDTH } from './constants';

export function NewAPI(props) {
  const history = useHistory();
  const location = useLocation();
  const params = useParams();

  useEffect(() => {
    props.setTitle({
      value: 'Create a new API',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  const [value, setValue] = useState();

  const [step, setStep] = useState(0);
  const [choice, setChoice] = useState();

  useEffect(() => {
    if (choice === 'fromScratch') {
      nextClient
        .forEntityNext(nextClient.ENTITIES.APIS)
        .template()
        .then((data) =>
          setValue({
            ...data,
            id: params.apiId,
          })
        );
    } else {
      setValue({
        openapi: 'https://petstore3.swagger.io/api/v3/openapi.json',
        domain: 'petstore.oto.tools',
        contextPath: '/v1',
        step: 0,
        api: undefined,
        backendHostname: 'petstore3.swagger.io',
        backendPath: '/api/v3/',
      });
    }
  }, [choice]);

  const schema = {
    location: {
      type: 'location',
      props: {
        collapsed: false,
        collapsable: false,
      },
    },
    id: { type: 'string', disabled: true, props: { label: 'id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name' },
    },
    description: {
      type: 'string',
      props: { label: 'Description' },
    },
    openapi: {
      type: 'string',
      label: 'OpenAPI URL',
    },
    domain: {
      type: 'string',
      label: 'API domain',
    },
    contextPath: {
      type: 'string',
      label: 'Context path',
    },
    backendHostname: {
      type: 'string',
      label: 'Backend hostname',
    },
    backendPath: {
      type: 'string',
      label: 'Backend path',
    },
    action: {
      renderer: (props) => {
        return (
          <Row title=" " className="col-sm-10 d-flex align-items-center">
            <Button
              type="primaryColor"
              disabled={
                !(
                  props.rootValue.domain &&
                  props.rootValue.contextPath &&
                  props.rootValue.backendHostname &&
                  props.rootValue.backendPath
                )
              }
              className="btn-sm"
              text="Read information from OpenAPI"
              onClick={() => {
                fetchWrapperNext(
                  `/${nextClient.ENTITIES.APIS}/_openapi`,
                  'POST',
                  value,
                  'apis.otoroshi.io'
                ).then((api) => {
                  const hasBackends = api.backends?.length > 0;
                  const firstBackend = hasBackends ? api.backends[0] : undefined;

                  setValue({
                    ...value,
                    serverURL:
                      hasBackends && firstBackend.backend.targets.length > 0
                        ? firstBackend.backend.targets[0].hostname
                        : '',
                    root: hasBackends && firstBackend.root ? firstBackend.root : '',
                    api,
                  });
                });
              }}
            />
          </Row>
        );
      },
    },
    picker: {
      renderer: (_) => {
        return (
          <div className="d-flex flex-column align-items-center">
            <h3 className="mt-3">Which method do you want to explore first ?</h3>
            <span className="mb-3">Please choose one for now</span>
            <div className="d-flex flex-column gap-2" style={{ flexWrap: 'wrap' }}>
              {[
                {
                  type: 'fromScratch',
                  title: 'Build from Scratch',
                  desc: 'Design your API manually without using an OpenAPI specification',
                },
                {
                  type: 'openapi',
                  title: 'Import OpenAPI',
                  desc: 'Provide your OpenAPI definition to generate the API automatically',
                },
              ].map(({ type, desc, title }) => (
                <Button
                  className="d-flex align-items-center gap-3"
                  style={{
                    color: 'var(--text)',
                  }}
                  onClick={() => setChoice(type)}
                  type={type === choice ? 'primaryColor' : 'secondary'}
                >
                  <div className="d-flex flex-column align-items-start">
                    <p className="m-0" style={{ fontWeight: 'bold' }}>
                      {title}
                    </p>
                    {desc}
                  </div>
                  {type === choice ? (
                    <div
                      style={{
                        borderRadius: '50%',
                        border: '1px solid var(--text)',
                        width: '1.5rem',
                        height: '1.5rem',
                        fontSize: '.75rem',
                      }}
                      className="d-flex align-items-center justify-content-center"
                    >
                      <i className="fa fa-check" />
                    </div>
                  ) : (
                    <div style={{ width: '1.5rem' }} />
                  )}
                </Button>
              ))}
              <Button
                className="ms-auto"
                type={choice ? 'primaryColor' : 'secondary'}
                disabled={!choice}
                onClick={() => {
                  setStep(1);
                }}
              >
                Continue
              </Button>
            </div>
          </div>
        );
      },
    },
  };

  const flow =
    step === 0
      ? ['picker']
      : [
          'location',
          choice === 'openapi'
            ? {
                type: 'group',
                name: 'OpenAPI',
                collapsable: false,
                fields: [
                  'openapi',
                  'domain',
                  'contextPath',
                  'backendHostname',
                  'backendPath',
                  'action',
                ],
              }
            : {
                type: 'group',
                name: 'Informations',
                collapsable: false,
                fields: ['id', 'name', 'description'],
              },
        ];

  const createApi = () => {
    if (choice === 'fromScratch') {
      nextClient
        .forEntityNext(nextClient.ENTITIES.APIS)
        .create(value)
        .then(() => historyPush(history, location, `/apis/${value.id}`));
    } else {
      fetchWrapperNext(
        `/${nextClient.ENTITIES.APIS}/_openapi`,
        'POST',
        value,
        'apis.otoroshi.io'
      ).then((api) => {
        nextClient
          .forEntityNext(nextClient.ENTITIES.APIS)
          .create(api)
          .then(() => historyPush(history, location, `/apis/${api.id}`));
      });
    }
  };

  if (!value) return <SimpleLoader />;

  return (
    <div className="mx-auto" style={{ maxWidth: MAX_WIDTH }}>
      <NgForm schema={schema} flow={flow} value={value} onChange={setValue} />
      {step === 1 && (
        <div className="d-flex justify-content-between align-items-center">
          <Button
            type="quiet"
            onClick={() => {
              setStep(0);
            }}
          >
            <i className="fa fa-chevron-left me-2" />
            Back
          </Button>
          <Button
            type="primaryColor"
            className="d-flex"
            onClick={createApi}
            text="Create"
            disabled={choice !== 'fromScratch' && !value.api}
          />
        </div>
      )}
    </div>
  );
}

export function Apis(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  useEffect(() => {
    props.setTitle({
      value: 'APIs',
      noThumbtack: true,
    });
  }, []);

  const columns = [
    {
      title: 'Name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      content: (item) => item.description,
    },
    {
      title: 'Enabled',
      id: 'enabled',
      style: { textAlign: 'center', width: 90 },
      notFilterable: true,
      cell: (enabled) =>
        enabled ? (
          <span className="fas fa-check-circle" style={{ color: 'var(--color-green)' }} />
        ) : (
          <span className="fas fa-times" style={{ color: 'var(--color-red)' }} />
        ),
    },
    {
      title: 'Version',
      content: (item) => item.version,
      cell: (value) => (
        <span
          className="badge custom-badge api-status-started"
          style={{
            fontSize: '.75rem',
          }}
        >
          {value}
        </span>
      ),
      notFilterable: true,
    },
    {
      title: 'State',
      content: (item) => item.state,
      notFilterable: true,
      cell: (value) => <APIState value={value} />,
    },
  ];

  const fetchItems = (paginationState) =>
    nextClient.forEntityNext(nextClient.ENTITIES.APIS).findAllWithPagination(paginationState);

  const fetchTemplate = () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).template();

  return (
    <>
      <InfoCollapse title="What is an API?">
        <p>
          An API is one of the <strong>core entities</strong> of Otoroshi's API Management,
          alongside HTTP Routes. While a HTTP route handles a single routing rule, an API lets you{' '}
          <strong>aggregate multiple routes together</strong> and manage them as a unified whole —
          same security policies, same plugins, same dashboard.
        </p>
        <p>
          Think of it as a higher-level abstraction that brings structure and governance to your
          routes. Here is what you can do with APIs:
        </p>
        <ul>
          <li>
            <strong>Group routes under one umbrella</strong> — combine multiple routes (e.g.{' '}
            <code>/users</code>, <code>/products</code>, <code>/orders</code>) into a single API
            with shared configuration.
          </li>
          <li>
            <strong>Apply consistent security</strong> — enforce the same authentication, rate
            limiting, and access control policies across all routes of the API.
          </li>
          <li>
            <strong>Share plugins and patterns</strong> — define plugin chains once at the API level
            and have them apply to every route, avoiding duplication.
          </li>
          <li>
            <strong>Unified dashboard</strong> — monitor traffic, errors, and performance for all
            routes of the API from a single view.
          </li>
          <li>
            <strong>Version and deploy</strong> — manage the lifecycle of your API with versioned
            deployments, making it easy to evolve your API over time.
          </li>
          <li>
            <strong>Draft and production modes</strong> — work on a draft version of your API, test
            it, and promote it to production when ready — without impacting live traffic.
          </li>
          <li>
            <strong>Manage access modes and subscriptions</strong> — control who can access your
            API, issue API keys, and track access mode usage.
          </li>
        </ul>
        <p>
          APIs give you the power to operate at scale — instead of managing dozens of individual
          routes, you manage a single API entity with full control over its lifecycle, security, and
          observability.
        </p>
      </InfoCollapse>
      <Table
        parentProps={{ params }}
        navigateTo={(item) => historyPush(history, location, `/apis/${item.id}`)}
        navigateOnEdit={(item) => historyPush(history, location, `/apis/${item.id}`)}
        selfUrl="apis"
        defaultTitle="Api"
        itemName="Api"
        formSchema={null}
        formFlow={null}
        columns={columns}
        deleteItem={(item) =>
          nextClient.forEntityNext(nextClient.ENTITIES.APIS).deleteById(item.id)
        }
        defaultSort="name"
        defaultSortDesc="true"
        fetchItems={fetchItems}
        fetchTemplate={fetchTemplate}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${i.id}`)}
        rawEditUrl={true}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link
              className="btn btn-primary btn-sm"
              to="#"
              onClick={() => {
                nextClient
                  .forEntityNext(nextClient.ENTITIES.APIS)
                  .template()
                  .then((api) => {
                    history.push(`apis/${api.id}/new?version=staging`);
                  });
              }}
            >
              <i className="fas fa-plus-circle" /> Create new API
            </Link>
            {props.injectTopBar}
          </div>
        )}
      />
    </>
  );
}
