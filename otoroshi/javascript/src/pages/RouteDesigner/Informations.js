import React, { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { Form, type, constraints, format } from '@maif/react-forms';
import { Location } from '../../components/Location';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory, useLocation } from 'react-router-dom';
import { useEntityFromURI } from '../../util';
import isEqual from 'lodash/isEqual';
import merge from 'lodash/merge';
import { FeedbackButton } from './FeedbackButton';

export const Informations = forwardRef(({ isCreation, value, setValue, setSaveButton }, ref) => {
  const history = useHistory();
  const location = useLocation();
  const [informations, setInformations] = useState({ ...value });

  const { capitalize, lowercase, fetchName, link } = useEntityFromURI();

  useEffect(() => {
    setInformations({ ...value });
  }, [value]);

  useImperativeHandle(ref, () => ({
    onTestingButtonClick() {
      history.push(`/routes/${value.id}?tab=flow`, { showTryIt: true });
    },
  }));

  useEffect(() => {
    setSaveButton(saveButton('ms-2'));
  }, [informations]);

  const saveButton = (className) => {
    return (
      <FeedbackButton
        className={className}
        onPress={saveRoute}
        text={isCreation ? 'Create route' : 'Save route'}
        _disabled={isEqual(informations, value)}
        icon={() => <i className="fas fa-paper-plane" />}
      />
    );
  };

  const saveRoute = () => {
    if (isCreation) {
      return nextClient
        .create(nextClient.ENTITIES[fetchName], informations)
        .then(() => history.push(`/${link}/${informations.id}?tab=flow`));
    } else
      return nextClient.update(nextClient.ENTITIES[fetchName], informations).then((res) => {
        if (!res.error) setValue(res);
      });
  };

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
    capture: {
      type: type.bool,
      label: 'Capture route traffic',
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
    'capture',
    'description',
    'groups',
    {
      label: 'Advanced',
      flow: ['metadata', 'tags'],
      collapsed: false,
    },
  ];

  if (!informations || !value) return null;

  return (
    <>
      <Form
        schema={{
          ...schema,
          id: {
            ...schema.id,
            disabled: true
          }
        }}
        flow={flow}
        value={informations}
        options={{ autosubmit: true }}
        onSubmit={(item) => {
          setInformations({
            ...item,
            backend: value.backend,
            backend_ref: value.backend_ref,
            frontend: value.frontend,
            plugins: value.plugins
          })
        }}
        footer={() => null}
      />
      <div className="d-flex align-items-center justify-content-end mt-3">
        <div className="btn-group">
          <button className="btn btn-sm btn-danger" onClick={() => history.push(`/${link}`)}>
            <i className="fas fa-times" /> Cancel
          </button>
          {!isCreation && (
            <button
              className="btn btn-sm btn-danger"
              onClick={() => {
                window.newConfirm('Are you sure you want to delete that route ?').then((ok) => {
                  if (ok) {
                    nextClient
                      .deleteById(nextClient.ENTITIES[fetchName], value.id)
                      .then(() => history.push(`/${link}`));
                  }
                });
              }}>
              <i className="fas fa-trash" /> Delete
            </button>
          )}
          {saveButton('')}
        </div>
      </div>
    </>
  );
});
