import React, { useRef } from 'react';
import { Form, type, constraints, format } from '@maif/react-forms';
import { Location } from '../../components/Location';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory } from 'react-router-dom';

export const Informations = (props) => {
  const ref = useRef();
  const history = useHistory();

  const schema = {
    id: {
      type: type.string,
      visible: false,
    },
    name: {
      type: type.string,
      label: 'Route name',
      placeholder: 'Your route name',
      help: 'The name of your route. Only for debug and human readability purposes.',
      constraints: [constraints.required()],
    },
    enabled: {
      type: type.bool,
      label: 'Route enabled',
    },
    debug_flow: {
      type: type.bool,
      label: 'Debug the flow',
    },
    export_reporting: {
      type: type.bool,
      label: 'Export reporting',
    },
    description: {
      type: type.string,
      label: 'Description',
      placeholder: 'Your route description',
      help: 'The description of your route. Only for debug and human readability purposes.',
    },
    groups: {
      type: type.string,
      format: format.select,
      createOption: true,
      isMulti: true,
      label: 'Groups',
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
      label: null,
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
    {
      label: 'Location',
      flow: ['_loc'],
      collapsed: false,
    },
    'id',
    'name',
    'enabled',
    'debug_flow',
    'export_reporting',
    'description',
    'groups',
    {
      label: 'Advanced',
      flow: ['metadata', 'tags'],
      collapsed: false,
    },
  ];

  return (
    <div className="designer-form">
      <Form
        schema={schema}
        flow={flow}
        value={
          props.isCreation
            ? {
                ...props.value,
                name: '',
                description: '',
              }
            : props.value
        }
        ref={ref}
        onSubmit={(item) => {
          if (props.isCreation)
            nextClient
              .create(nextClient.ENTITIES.ROUTES, item)
              .then(() => history.push(`/routes/${item.id}?tab=flow`));
          else nextClient.update(nextClient.ENTITIES.ROUTES, item);
        }}
        footer={() => null}
      />
      <div className="d-flex align-items-center justify-content-end mt-3">
        <button className="btn btn-sm btn-danger" onClick={() => history.push('/routes')}>
          Cancel
        </button>
        <button className="btn btn-sm btn-save ms-1" onClick={() => ref.current.handleSubmit()}>
          {props.isCreation ? 'Create the route' : 'Update the route'}
        </button>
      </div>
    </div>
  );
};
