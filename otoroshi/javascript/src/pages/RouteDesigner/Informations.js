import React, { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { NgForm } from '../../components/nginputs';
import { Location } from '../../components/Location';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory } from 'react-router-dom';
import { useEntityFromURI } from '../../util';
import isEqual from 'lodash/isEqual';
import { FeedbackButton } from './FeedbackButton';

export const Informations = forwardRef(({ isCreation, value, setValue, setSaveButton }, ref) => {
  const history = useHistory();
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
      type: 'string',
      visible: false
    },
    name: {
      type: 'string',
      label: `${capitalize} name`,
      placeholder: `Your ${lowercase} name`,
      help: `The name of your ${lowercase}. Only for debug and human readability purposes.`,
      // constraints: [constraints.required()],
    },
    enabled: {
      type: 'bool',
      label: 'Route enabled',
    },
    capture: {
      type: 'bool',
      label: 'Capture route traffic',
    },
    debug_flow: {
      type: 'bool',
      label: 'Debug the flow',
    },
    export_reporting: {
      type: 'bool',
      label: 'Export reporting',
    },
    description: {
      type: 'string',
      label: 'Description',
      placeholder: 'Your route description',
      help: 'The description of your route. Only for debug and human readability purposes.',
    },
    groups: {
      type: 'string',
      format: 'select',
      createOption: true,
      isMulti: true,
      label: 'Groups',
    },
    metadata: {
      type: 'object',
      label: 'Metadata',
    },
    tags: {
      format: 'select',
      createOption: true,
      isMulti: true,
      label: 'Tags',
    },
    _loc: {
      type: 'object',
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
    // '>>>Location',
    '_loc',
    'id',
    'name',
    'enabled',
    'debug_flow',
    'export_reporting',
    'capture',
    'description',
    'groups',
    // '>>>Advanced',
    'metadata',
    'tags'
  ];

  if (!informations || !value) return null;

  return (
    <>
      <NgForm
        schema={schema}
        flow={flow}
        value={informations}
        onChange={(value, validation) => {
          // TODO - manage validation
          setInformations(value)
        }}
      />
      <div className="d-flex align-items-center justify-content-end mt-3">
        <div className="btn-group">
          <button className="btn btn-sm btn-danger" onClick={() => history.push(`/${link}`)}>
            <i className="fas fa-times" /> Cancel
          </button>
        </div>
      </div>
    </>
  );
});
