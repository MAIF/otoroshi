import React, { useState } from 'react';
import { NgSelectRenderer } from '../../components/nginputs';
import { FeedbackButton } from './FeedbackButton';
import { convertAsRoute } from '../../services/BackOfficeServices';
import { useHistory } from 'react-router-dom';

export function ImportServiceDescriptor({ hide }) {
  const [service, setService] = useState();
  const history = useHistory();

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
            onPress={() =>
              convertAsRoute(service)
                .then((res) => {
                  history.push(`/routes/${res.id}?tab=informations`, {
                    routeFromService: res,
                  })
                })
            }
            icon={() => <i className="fas fa-paper-plane" />}
          />
        </div>
      </div>
    </div>
  );
}
