import React, { useEffect, useRef, useState } from 'react';
import { useHistory, useParams, Link, Switch, Route, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { Location } from '../components/Location';
import { Form, format, type } from '@maif/react-forms';
import { toUpperCaseLabels } from '../util';
import { FeedbackButton } from './RouteDesigner/FeedbackButton';
import { DEFAULT_FLOW } from './RouteDesigner/Graph';

export const BackendsPage = ({ setTitle }) => {
  const params = useParams();
  const history = useHistory();
  const match = useRouteMatch();

  const BackendsTable = () => (
    <div className="designer">
      <Table
        parentProps={{ params }}
        navigateTo={(item) => history.push(`/backends/${item.id}`)}
        selfUrl="backends"
        defaultTitle="Backends"
        itemName="Backend"
        formSchema={null}
        formFlow={null}
        columns={[{ title: 'Name', content: (item) => item.name }]}
        fetchItems={() => nextClient.find(nextClient.ENTITIES.BACKENDS)}
        deleteItem={item => nextClient.remove(nextClient.ENTITIES.BACKENDS, item)}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        rawEditUrl={true}
        injectTopBar={() => (
          <Link className="btn btn-success ms-1" to={'backends/new'}>
            <i className="fas fa-plus-circle" /> Create new backend
          </Link>
        )}
      />
    </div>
  );

  useEffect(() => {
    setTitle('Backends');
  }, []);

  return (
    <Switch>
      <Route
        exact
        path={`${match.url}/:backendId`}
        component={() => {
          const p = useParams();
          const isCreation = p.backendId === 'new';

          const [value, setValue] = useState({});

          useEffect(() => {
            if (p.backendId === 'new')
              nextClient.template(nextClient.ENTITIES.BACKENDS).then(setValue);
            else nextClient.fetch(nextClient.ENTITIES.BACKENDS, p.backendId).then(setValue);
          }, [p.routebackendIdId]);

          return (
            <div style={{ padding: '7px 15px 0 0' }} className="designer row">
              <div className="col-sm-11" style={{ paddingLeft: 0 }}>
                <BackendForm isCreation={isCreation} value={value} />
              </div>
            </div>
          );
        }}
      />
      <Route component={BackendsTable} />
    </Switch>
  );
};

export const BackendForm = ({ isCreation, value, foldable, style = {} }) => {
  const ref = useRef();
  const history = useHistory();
  const [show, setShow] = useState(!foldable);

  const [form, setForm] = useState({
    schema: {},
    flow: [],
  });

  const schema = {
    id: {
      type: type.string,
      visible: false,
    },
    name: {
      label: 'Name',
      type: type.string,
    },
    description: {
      label: 'Description',
      type: type.string,
    },
    metadata: {
      type: type.object,
      label: 'Metadata',
    },
    tags: {
      type: type.string,
      format: format.select,
      createOption: true,
      isMulti: true,
      label: 'Tags',
    },
    _loc: {
      type: type.object,
      label: 'Location',
      render: ({ onChange, value }) => (
        <Location
          {...value}
          onChangeTenant={(v) =>
            onChange({
              ...value,
              tenant: v,
            })
          }
          onChangeTeams={(v) =>
            onChange({
              ...value,
              teams: v,
            })
          }
        />
      ),
    },
  };

  const flow = [
    'name',
    'description',
    'backend',
    {
      label: 'Advanced',
      flow: ['metadata', 'tags'],
      collapsed: true,
    },
    {
      label: 'Location',
      flow: ['_loc'],
      collapsed: true,
    },
  ];

  // TODO - surcharger le schema du backend pour pas que ça pointe tout le temps sur plugin. mais backend.

  useEffect(() => {
    nextClient.form(nextClient.ENTITIES.BACKENDS).then((res) => {
      setForm({
        schema: {
          ...schema,
          backend: {
            type: 'object',
            format: 'form',
            schema: toUpperCaseLabels(DEFAULT_FLOW.Backend('backend').config_schema(res.schema)),
            flow: DEFAULT_FLOW.Backend('backend').config_flow,
          },
        },
        flow
      });
    });
  }, []);

  console.log(form, value)

  return (
    <div
      className="designer-form"
      style={{
        ...style,
        minHeight: show ? 'calc(100vh - 85px)' : 'initial',
      }}>
      <div className="d-flex align-items-center my-1">
        {foldable && (
          <i
            className={`me-2 fas fa-chevron-${show ? 'up' : 'right'}`}
            style={{ fontSize: '20px', color: '#eee', cursor: 'pointer' }}
            onClick={() => setShow(!show)}
          />
        )}
        <h4 style={{ margin: foldable ? 0 : 'inherit' }}>{value.name || 'Backend'}</h4>
      </div>
      {show && (
        <>
          <Form
            schema={form.schema}
            flow={form.flow}
            value={value}
            ref={ref}
            onError={(e) => console.log(e)}
            onSubmit={() => { }}
            footer={() => <FeedbackButton
              className="d-flex ms-auto my-3"
              text={isCreation ? 'Create the backend' : 'Update the backend'}
              icon={() => <i className="fas fa-paper-plane" />}
              onPress={() => ref.current.trigger()
                .then(res => {
                  if (res) {
                    if (isCreation)
                      nextClient
                        .create(nextClient.ENTITIES.BACKENDS, ref.current.methods.data())
                        .then(() => history.push(`/backends`));
                    else
                      nextClient.update(nextClient.ENTITIES.BACKENDS, ref.current.methods.data())
                        .then(r => {
                          if (r.error)
                            throw r.error_description
                        })
                  } else
                    throw ""
                })}
            />}
          />
        </>
      )}
    </div>
  );
};
