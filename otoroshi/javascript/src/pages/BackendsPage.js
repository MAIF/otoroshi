import React, { useEffect, useRef, useState } from 'react';
import { useHistory, useParams, Link, Switch, Route, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { Location } from '../components/Location';
import { Form, format, type } from '@maif/react-forms';
import { toUpperCaseLabels } from '../util';
import { FeedbackButton } from './RouteDesigner/FeedbackButton';
import { DEFAULT_FLOW } from './RouteDesigner/Graph';
import { isEqual, merge } from 'lodash';

export const BackendsPage = ({ setTitle }) => {
  const params = useParams();
  const history = useHistory();
  const match = useRouteMatch();

  useEffect(() => {
    patchStyle(true);

    return () => patchStyle(false);
  }, []);

  const patchStyle = (applyPatch) => {
    if (applyPatch) {
      document.getElementsByClassName('main')[0].classList.add('patch-main');
      [...document.getElementsByClassName('row')].map((r) => r.classList.add('patch-row', 'g-0'));
    } else {
      document.getElementsByClassName('main')[0].classList.remove('patch-main');
      [...document.getElementsByClassName('row')].map((r) =>
        r.classList.remove('patch-row', 'g-0')
      );
    }
  };

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
        deleteItem={(item) => nextClient.remove(nextClient.ENTITIES.BACKENDS, item)}
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
            <div className="designer row">
              <BackendForm isCreation={isCreation} value={value} setTitle={setTitle} />
            </div>
          );
        }}
      />
      <Route component={BackendsTable} />
    </Switch>
  );
};

export const BackendForm = ({ isCreation, value, setTitle }) => {
  const history = useHistory();
  const [form, setForm] = useState({
    schema: {},
    flow: [],
  });

  const [state, setState] = useState({ ...value });

  useEffect(() => setState({ ...value }), [value]);

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

  useEffect(() => {
    setTitle(() => (
      <div className="page-header d-flex align-item-center justify-content-between ms-0 mb-3">
        <h4 className="flex" style={{ margin: 0 }}>
          {state.name || 'Backend'}
        </h4>
        <div className="d-flex align-item-center justify-content-between flex">
          {saveButton(false)}
        </div>
      </div>
    ));
  }, [state]);

  useEffect(() => {
    nextClient.form(nextClient.ENTITIES.BACKENDS).then((res) => {
      setForm({
        schema: {
          ...schema,
          backend: {
            type: 'object',
            label: null,
            format: 'form',
            schema: toUpperCaseLabels(DEFAULT_FLOW.Backend('backend').config_schema(res.schema)),
            flow: DEFAULT_FLOW.Backend('backend').config_flow,
          },
        },
        flow,
      });
    });
  }, []);

  const saveButton = (withMargin = true) => (
    <FeedbackButton
      className={withMargin ? 'd-flex ms-auto my-3' : ''}
      disabled={isEqual(state, value)}
      text={isCreation ? 'Create the backend' : 'Update the backend'}
      icon={() => <i className="fas fa-paper-plane" />}
      onPress={update}
    />
  );

  const update = () => {
    if (isCreation)
      return nextClient
        .create(nextClient.ENTITIES.BACKENDS, state)
        .then(() => history.push(`/backends`));
    else
      return nextClient.update(nextClient.ENTITIES.BACKENDS, state).then((r) => {
        if (r.error) throw r.error_description;
      });
  };

  if (!state || Object.keys(state).length === 0) return null;

  return (
    <Form
      schema={form.schema}
      flow={form.flow}
      value={state}
      onError={(e) => console.log(e)}
      options={{ autosubmit: true }}
      onSubmit={(item) => setState({ ...merge({ ...value }, item) })}
      footer={saveButton}
    />
  );
};
