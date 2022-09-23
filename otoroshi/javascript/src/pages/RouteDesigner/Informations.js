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
      props: {
        label: 'Route enabled',
        labelColumn: 6
      }
    },
    capture: {
      type: 'bool',
      props: {
        label: 'Capture route traffic',
        labelColumn: 6
      }
    },
    debug_flow: {
      type: 'bool',
      props: {
        label: 'Debug the flow',
        labelColumn: 6
      }
    },
    export_reporting: {
      type: 'bool',
      props: {
        label: 'Export reporting',
        labelColumn: 6
      }
    },
    description: {
      type: 'string',
      label: 'Description',
      placeholder: 'Your route description',
      help: 'The description of your route. Only for debug and human readability purposes.',
    },
    groups: {
      type: 'array-select',
      props: {
        optionsFrom: "/bo/api/proxy/api/groups",
        optionsTransformer: arr => arr.map(item => ({ value: item.id, label: item.name })),
        label: 'Groups'
      }
    },
    metadata: {
      type: 'object',
      label: 'Metadata',
    },
    tags: {
      type: 'array',
      props: {
        label: 'Tags'
      }
    },
    _loc: {
      type: 'location',
      props: {
        label: 'Location'
      }
    }
  };

  const flow = [
    '_loc',
    '#group|Route|name,description,groups',
    '#grid|Flags|enabled,debug_flow,export_reporting,capture',
    '#group|Advanced|metadata,tags'
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
        <div className="displayGroupBtn">
          <button className="btn btn-danger" onClick={() => history.push(`/${link}`)}>
            Cancel
          </button>
        </div>
      </div>
    </>
  );
});
