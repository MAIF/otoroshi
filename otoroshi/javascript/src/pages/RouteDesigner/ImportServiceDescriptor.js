import React, { useState } from 'react';
import { NgSelectRenderer } from '../../components/nginputs';
import { FeedbackButton } from './FeedbackButton';
import { convertAsRoute } from '../../services/BackOfficeServices';
import { useHistory } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import moment from 'moment';

export function ImportServiceDescriptor({ hide }) {
  const [service, setService] = useState();
  const history = useHistory();

  function conversion() {
    return convertAsRoute(service).then((res) => {
      return BackOfficeServices.fetchService('prod', service).then((fullService) => {
        const newService = {
          ...fullService,
          name: '[MIGRATED TO ROUTE, PENDING DELETION] ' + fullService.name,
          enabled: false,
          metadata: {
            ...fullService.metadata,
            converted_to_route_by: window.__user.email + ' - ' + window.__user.name,
            converted_to_route_at: moment().format('YYYY-MM-DD hh:mm:ss'),
          },
        };
        return BackOfficeServices.updateService(newService.id, newService).then(() => {
          return BackOfficeServices.nextClient
            .forEntity('routes')
            .create({
              ...res,
              metadata: {
                ...res.metadata,
                converted_from_service_by: window.__user.email + ' - ' + window.__user.name,
                converted_from_service_at: moment().format('YYYY-MM-DD hh:mm:ss'),
              },
            })
            .then(() => {
              history.push(`/routes/${res.id}?tab=informations`);
            });
        });
      });
    });
    /*
    convertAsRoute(service).then((res) => {
      history.push(`/routes/${res.id}?tab=informations`, {
        routeFromService: res,
      });
    })*/
  }

  return (
    <div className="wizard">
      <div className="wizard-container">
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '2.5rem' }}>
          <label style={{ fontSize: '1.15rem' }}>
            <i className="fas fa-times me-3" onClick={hide} style={{ cursor: 'pointer' }} />
            <span>Import a service descriptor</span>
          </label>

          <div className="wizard-content">
            <NgSelectRenderer
              value={service}
              ngOptions={{
                spread: true,
              }}
              onChange={(service) => {
                setService(service);
              }}
              optionsTransformer={(arr) =>
                arr
                  .filter((item) => item.name !== 'otoroshi-admin-api')
                  .map((item) => ({ label: item.name, value: item.id }))
              }
              optionsFrom="/bo/api/proxy/api/services"
            />
          </div>
          <FeedbackButton
            disabled={!service}
            text="Migrate and start editing"
            className="mt-3"
            onPress={() => conversion()}
            icon={() => <i className="fas fa-paper-plane" />}
          />
        </div>
      </div>
    </div>
  );
}
