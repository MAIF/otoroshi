import React, { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { NgForm } from '../../components/nginputs';
import { nextClient } from '../../services/BackOfficeServices';
import { useHistory, useLocation } from 'react-router-dom';
import { useEntityFromURI } from '../../util';
import { FeedbackButton } from './FeedbackButton';
import { RouteForm } from './form';
import { Button } from '../../components/Button';
import { ENTITIES, FormSelector } from '../../components/FormSelector';

export const Informations = forwardRef(
  ({ isCreation, value, setValue, setSaveButton, routeId }, ref) => {
    const history = useHistory();
    const location = useLocation();
    const [showAdvancedForm, toggleAdvancedForm] = useState(false);

    const { capitalize, lowercase, fetchName, link } = useEntityFromURI();

    const isOnRouteCompositions = location.pathname.includes('route-compositions');
    const entityName = isOnRouteCompositions ? 'route composition' : 'route';

    useImperativeHandle(ref, () => ({
      onTestingButtonClick() {
        history.push(`/${link}/${value.id}?tab=flow`, { showTryIt: true });
      },
    }));

    useEffect(() => {
      setSaveButton(
        <FeedbackButton
          className="ms-2"
          onPress={saveRoute}
          text={isCreation ? `Create ${entityName}` : `Save ${entityName}`}
          icon={() => <i className="fas fa-paper-plane" />}
        />
      );
    }, [value]);

    function saveRoute() {
      if (isCreation || location.state?.routeFromService) {
        return nextClient
          .create(nextClient.ENTITIES[fetchName], value)
          .then(() => history.push(`/${link}/${value.id}?tab=flow`));
      } else {
        return nextClient.update(nextClient.ENTITIES[fetchName], value).then((res) => {
          if (!res.error) setValue(res);
        });
      }
    }

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
        label: 'Enabled',
        props: {},
      },
      capture: {
        type: 'bool',
        label: 'Capture route traffic',
        props: {
          labelColumn: 3,
        },
      },
      debug_flow: {
        type: 'bool',
        label: 'Debug the route',
        props: {
          labelColumn: 3,
        },
      },
      export_reporting: {
        type: 'bool',
        label: 'Export reporting',
        props: {
          labelColumn: 3,
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
        label: 'Groups',
        props: {
          optionsFrom: '/bo/api/proxy/api/groups',
          optionsTransformer: (arr) => arr.map((item) => ({ value: item.id, label: item.name })),
        },
      },
      metadata: {
        type: 'object',
        label: 'Metadata',
      },
      tags: {
        type: 'string',
        array: true,
        label: 'Tags',
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

    return (
      <>
        {showAdvancedForm ?
          <RouteForm
            routeId={routeId}
            setValue={setValue}
            value={value}
            history={history}
            location={location}
            isCreation={isCreation} />
          :
          <NgForm
            schema={schema}
            flow={flow}
            value={value}
            onChange={v => {
              setValue(v)
            }}
          />}

        <div className="d-flex align-items-center justify-content-end mt-3 p-0">
          {!isOnRouteCompositions && (
            <FormSelector onChange={toggleAdvancedForm} entity={ENTITIES.ROUTES} className="me-1" />
          )}
          <Button
            type="danger"
            className="btn-sm"
            onClick={() => history.push(`/${link}`)}
            text="Cancel"
          />
        </div>
      </>
    );
  }
);
