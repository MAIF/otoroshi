import React, { useRef } from 'react';
import { Form, type, constraints, format } from '@maif/react-forms';
import { Location } from '../../components/Location';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory } from 'react-router-dom';
import { useEntityFromURI } from '../../util';

export const Informations = ({ isCreation, value, setValue }) => {
  const ref = useRef();
  const history = useHistory();

  const { capitalize, lowercase, fetchName, link } = useEntityFromURI()

  const schema = {
    id: {
      type: type.string,
      visible: false,
    },
    name: {
      type: type.string,
      label: `${capitalize} name`,
      placeholder: `Your ${lowercase} name`,
      help: `The name of your ${lowercase}. Only for debug and human readability purposes.`,
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
          isCreation
            ? {
              ...value,
              name: '',
              description: '',
            }
            : value
        }
        ref={ref}
        onSubmit={(item) => {
          if (isCreation) {
            nextClient
              .create(nextClient.ENTITIES[fetchName], item)
              .then(() => history.push(`/${link}/${item.id}?tab=flow`));
          } else
            nextClient.update(nextClient.ENTITIES[fetchName], item)
              .then(res => {
                if (!res.error)
                  setValue(res)
              })
        }}
        footer={() => null}
      />
      <div className="d-flex align-items-center justify-content-end mt-3">
        <div className="btn-group">
          <button className="btn btn-sm btn-danger" onClick={() => history.push(`/${link}`)}>
            <i className="fas fa-times" /> Cancel
          </button>
          {!isCreation && <button className="btn btn-sm btn-danger" onClick={() => nextClient.deleteById(nextClient.ENTITIES[fetchName], value.id).then(() => history.push(`/${link}`))}>
            <i className="fas fa-trash" /> Delete
          </button>}
          <button className="btn btn-sm btn-save" onClick={() => ref.current.handleSubmit()}>
            <i className="fas fa-save" /> {isCreation ? `Create the ${lowercase}` : `Update the ${lowercase}`}
          </button>
        </div>
      </div>
    </div>
  );
};
