import React, { Suspense, useEffect, useState } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';

const CodeInput = React.lazy(() => Promise.resolve(require('../components/inputs/CodeInput')));

export function EurekaServerPage(props) {
  const { setTitle, params } = props;
  const [apps, setApps] = useState({});

  const [instances, setInstances] = useState(0);
  const [globalStatus, setGlobalStatus] = useState(true);

  useEffect(() => {
    setTitle('Statuses');
    sync();
  }, []);

  const sync = () => {
    BackOfficeServices.getEurekaApps(params.eurekaServerId).then((apps) => {
      const groupedApps = groupByApp(apps);
      setApps(groupedApps);

      const status = calculateGlobalStatus(groupedApps);
      setGlobalStatus(
        status === Object.keys(groupedApps).length
          ? 'all up'
          : `${status}/${Object.keys(groupedApps).length}`
      );

      setNumberOfInstances(apps);

      setTimeout(sync, 30000);
    });
  };

  const calculateGlobalStatus = (apps) => {
    return Object.values(apps).reduce((up, instances) => {
      if (instances.find((instance) => instance.status !== 'UP')) return up - 1;
      else return up;
    }, Object.keys(apps).length);
  };

  const groupByApp = (apps) => {
    return apps.reduce((curr, app) => {
      if (!curr[app.application.name]) {
        return {
          ...curr,
          [app.application.name]: [app.application.instance],
        };
      } else {
        return {
          ...curr,
          [app.application.name]: [...curr[app.application.name], app.application.instance],
        };
      }
    }, {});
  };

  const setNumberOfInstances = (apps) => {
    const groupByInstances = groupByApp(apps);
    setInstances(Object.values(groupByInstances).reduce((acc, curr) => acc + curr.length, 0));
  };

  return (
    <div>
      <div
        className="d-flex"
        style={{
          justifyContent: 'space-around',
        }}>
        <div className="d-flex flex-column align-items-center">
          <span>APPLICATIONS</span>
          <span style={{ fontWeight: 'bold', fontSize: 24 }}>{Object.keys(apps).length}</span>
        </div>
        <div className="d-flex flex-column align-items-center">
          <span>INSTANCES</span>
          <span style={{ fontWeight: 'bold', fontSize: 24 }}>{instances}</span>
        </div>
        <div className="d-flex flex-column align-items-center">
          <span>STATUS</span>
          <span
            style={{
              fontWeight: 'bold',
              fontSize: 24,
              color: globalStatus === 'all up' ? 'var(--bs-green)' : 'var(--bs-danger)',
            }}>
            {globalStatus}
          </span>
        </div>
      </div>

      <Apps apps={apps} />
    </div>
  );
}

const Apps = ({ apps }) => {
  return (
    <div className="mt-3">
      {Object.entries(apps).map(([name, instances]) => (
        <App name={name} instances={instances} key={name} />
      ))}
    </div>
  );
};

const App = ({ name, instances }) => {
  return (
    <div
      className="mt-1 p-3"
      style={{
        backgroundColor: '#494948',
      }}>
      <span style={{ textTransform: 'uppercase' }}>{name}</span>
      {instances.map((instance) => (
        <div
          className="d-flex justify-content-between align-items-center py-3"
          key={instance.instanceId}>
          <div className="d-flex align-items-center">
            <div className="d-flex flex-column">
              <i
                className={`fas fa-${instance.status === 'UP' ? 'check' : 'times'} px-3`}
                style={{
                  color: instance.status === 'UP' ? 'var(--bs-green)' : 'var(--bs-danger)',
                }}
              />
              <Countdown lastHeartBeat={instance.last_heartbeat} />
            </div>
            <div className="d-flex flex-column">
              <span style={{ textTransform: 'uppercase' }}>{instance.instanceId}</span>
              <a href={instance.homePageUrl}>{instance.homePageUrl}</a>
            </div>
          </div>
          <button
            type="button"
            className="btn btn-success btn-sm me-3"
            onClick={() => {
              window.newAlert(
                <Suspense fallback="Loading ...">
                  <CodeInput hideLabel={true} value={JSON.stringify(instance, null, 2)} />
                </Suspense>,
                `${name} - ${instance.instanceId}`,
                undefined,
                {
                  maxWidth: '80%',
                }
              );
            }}>
            Informations
          </button>
        </div>
      ))}
    </div>
  );
};

const Countdown = ({ lastHeartBeat }) => {
  const [count, setCount] = useState();

  useEffect(() => {
    const interval = setInterval(() => {
      setCount(Date.now() - lastHeartBeat);
    });

    return () => clearInterval(interval);
  }, [lastHeartBeat]);

  return <span className="text-center">{Math.floor(count / 1000)}s</span>;
};
