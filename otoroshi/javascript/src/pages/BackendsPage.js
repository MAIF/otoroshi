import React, { useEffect, useRef, useState } from 'react';
import { useHistory, useParams, Link, Switch, Route, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { Location } from '../components/Location';
import { constraints, Form, format, type } from '@maif/react-forms';
import { camelToSnake, camelToSnakeFlow, toUpperCaseLabels } from '../util';

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
        deleteItem={() => nextClient.remove(nextClient.ENTITIES.BACKENDS)}
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

export const BackendForm = ({ isCreation, value, onSubmit, foldable, style = {} }) => {
  const ref = useRef();
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
    backend: {
      label: 'Backend',
      help: '',
      type: type.object,
      format: format.form,
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

  useEffect(() => {
    nextClient.form(nextClient.ENTITIES.BACKENDS).then((res) => {
      const formSchema = camelToSnake(toUpperCaseLabels(res.schema));
      const formFlow = res.flow.map((step) => camelToSnakeFlow(step));

      setForm({
        schema: {
          ...schema,
          backend: {
            ...schema.backend,
            schema: formSchema,
            flow: formFlow,
          },
        },
        flow,
      });
    });
  }, []);

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
            onSubmit={(item) => {
              if (isCreation)
                nextClient
                  .create(nextClient.ENTITIES.BACKENDS, item)
                  .then(() => history.push(`/backends`));
              else nextClient.update(nextClient.ENTITIES.BACKENDS, item);
            }}
            onError={(e) => console.log(e)}
            footer={() => null}
          />
          <button
            className="btn btn-success btn-block mt-3"
            onClick={() => ref.current.handleSubmit()}>
            {isCreation ? 'Create the backend' : 'Update the backend'}
          </button>
        </>
      )}
    </div>
  );
};
