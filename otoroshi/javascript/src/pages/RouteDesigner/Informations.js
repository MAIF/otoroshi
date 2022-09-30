import React, { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { NgForm } from '../../components/nginputs';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory, useLocation } from 'react-router-dom';
import { useEntityFromURI } from '../../util';
import isEqual from 'lodash/isEqual';
import { FeedbackButton } from './FeedbackButton';
import { RouteForm } from './form';

export const Informations = forwardRef(
  ({ isCreation, value, setValue, setSaveButton, routeId }, ref) => {
    const history = useHistory();
    const location = useLocation();
    const [informations, setInformations] = useState({ ...value });

    const [showAdvancedForm, toggleAdvancedForm] = useState(false);

    const { capitalize, lowercase, fetchName, link } = useEntityFromURI();

    useEffect(() => {
      setInformations({ ...value });
    }, [value]);

    useImperativeHandle(ref, () => ({
      onTestingButtonClick() {
        history.push(`/${link}/${value.id}?tab=flow`, { showTryIt: true });
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
      if (isCreation || location.state?.routeFromService) {
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
        visible: false,
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
          label: 'Enabled',
        },
      },
      capture: {
        type: 'bool',
        props: {
          label: 'Capture route traffic',
          labelColumn: 3,
        },
      },
      debug_flow: {
        type: 'bool',
        props: {
          label: 'Debug the flow',
          labelColumn: 6,
        },
      },
      export_reporting: {
        type: 'bool',
        props: {
          label: 'Export reporting',
          labelColumn: 6,
        },
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
          optionsFrom: '/bo/api/proxy/api/groups',
          optionsTransformer: (arr) => arr.map((item) => ({ value: item.id, label: item.name })),
          label: 'Groups',
        },
      },
      metadata: {
        type: 'object',
        label: 'Metadata',
      },
      tags: {
        type: 'array',
        props: {
          label: 'Tags',
        },
      },
      _loc: {
        type: 'location',
        props: {
          label: 'Location',
        },
      },
    };

    const flow = [
      {
        type: 'group',
        name: 'Expose your route',
        fields: ['enabled'],
      },
      '_loc',
      {
        type: 'group',
        name: 'Route',
        fields: [
          'name',
          'description',
          'groups',
          {
            type: 'grid',
            name: 'Flags',
            fields: ['debug_flow', 'export_reporting', 'capture'],
          },
        ],
      },
      {
        type: 'group',
        name: 'Misc.',
        collapsed: true,
        fields: ['metadata', 'tags'],
      },
    ];

    if (!informations || !value) return null;

    return (
      <>
        {showAdvancedForm && (
          <RouteForm routeId={routeId} setValue={setValue} history={history} location={location} />
        )}

        {!showAdvancedForm && (
          <NgForm
            schema={schema}
            flow={flow}
            value={informations}
            onChange={(value, validation) => {
              // TODO - manage validation
              setInformations(value);
            }}
          />
        )}

        <div className="d-flex align-items-center justify-content-end mt-3">
          <div className="displayGroupBtn">
            <button
              className="btn btn-outline-info"
              onClick={() =>
                showAdvancedForm ? toggleAdvancedForm(false) : toggleAdvancedForm(true)
              }>
              {showAdvancedForm ? 'Simple view' : 'Advanced view'}
            </button>
            <button className="btn btn-danger" onClick={() => history.push(`/${link}`)}>
              Cancel
            </button>
          </div>
        </div>
      </>
    );
  }
);
