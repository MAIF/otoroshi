import React from 'react';
import PageTitle from '../../components/PageTitle';
import { useHistory, useLocation } from 'react-router-dom';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import faker from 'faker';

export function Tab({ isActive, title, icon, to, fillBackground }) {
  const history = useHistory();

  return (
    <div className="ms-2" style={{ minHeight: 40 }}>
      <button
        type="button"
        className="btn btn-sm d-flex align-items-center h-100"
        onClick={() => {
          if (window.location.href !== to)
            history.replace({
              pathname: to,
            });
        }}
        style={{
          borderRadius: 6,
          backgroundColor: fillBackground ? 'var(--color-primary)' : 'transparent',
          boxShadow: `0 0 0 1px ${
            isActive ? 'var(--color-primary,transparent)' : 'var(--bg-color_level3,transparent)'
          }`,
          color: 'var(--text)',
        }}>
        {icon && <i className={`fas fa-${icon} me-2`} style={{ fontSize: '1.33333em' }} />}
        {title}
      </button>
    </div>
  );
}

export function ManagerTitle({}) {
  const location = useLocation();

  const editingGroup = location.pathname.startsWith('/extensions/green-score/groups/green-score');

  const isOnCreation = location.pathname.endsWith('new');

  const generate = async () => {
    const client = BackOfficeServices.apisClient(
      'green-score.extensions.otoroshi.io',
      'v1',
      'green-scores'
    );
    const routes = await BackOfficeServices.nextClient
      .forEntity(BackOfficeServices.nextClient.ENTITIES.ROUTES)
      .findAll();

    const rules = await fetch('/bo/api/proxy/api/extensions/green-score/template', {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then((r) => r.json());

    const getRandomInt = (min, max) => min + Math.floor(Math.random() * max);

    const dates = new Array(4)
      .fill(0)
      .map(() => {
        const today = new Date();
        today.setDate(today.getDate() - getRandomInt(2, 20)); // TODO - remove this line
        today.setUTCHours(0, 0, 0, 0);
        return today.getTime();
      })
      .sort()
      .reverse();

    const route = routes.find((r) => r.name.includes('static'));

    new Array(10).fill(0).map(async () => {
      const groupTemplate = await client.template();
      return client.create({
        ...groupTemplate,
        name: faker.name.firstName(),
        description: faker.lorem.words(),
        routes: [
          {
            routeId: route.id,
            rulesConfig: {
              states: dates.map((date) => {
                return {
                  date,
                  states: rules.slice(0, getRandomInt(2, rules.length)).map((rule) => {
                    return {
                      ...rule,
                      enabled: true,
                    };
                  }),
                };
              }),
            },
          },
        ],
        thresholds: {
          overhead: {
            excellent: getRandomInt(2, 5),
            sufficient: getRandomInt(10, 15),
            poor: getRandomInt(15, 50),
          },
          duration: {
            excellent: getRandomInt(2, 5),
            sufficient: getRandomInt(10, 15),
            poor: getRandomInt(15, 50),
          },
          backendDuration: {
            excellent: getRandomInt(2, 5),
            sufficient: getRandomInt(10, 15),
            poor: getRandomInt(15, 50),
          },
          calls: {
            excellent: getRandomInt(2, 5),
            sufficient: getRandomInt(10, 15),
            poor: getRandomInt(15, 50),
          },
          dataIn: {
            excellent: getRandomInt(20, 100),
            sufficient: getRandomInt(100, 300),
            poor: getRandomInt(300, 5000),
          },
          dataOut: {
            excellent: getRandomInt(20, 100),
            sufficient: getRandomInt(100, 300),
            poor: getRandomInt(300, 5000),
          },
          headersOut: {
            excellent: getRandomInt(20, 100),
            sufficient: getRandomInt(100, 300),
            poor: getRandomInt(300, 5000),
          },
          headersIn: {
            excellent: getRandomInt(20, 100),
            sufficient: getRandomInt(100, 300),
            poor: getRandomInt(300, 5000),
          },
        },
      });
    });
  };

  return (
    <PageTitle
      style={{
        paddingBottom: 0,
        border: 0,
        margin: '3rem auto 2.5rem',
      }}
      className="container-sm"
      title={'Green score'}>
      {!editingGroup && !isOnCreation && (
        <>
          {/* <button type="button" onClick={() => {
                    generate()
                }}>Generate</button> */}

          <Tab
            title="Dashboard"
            icon="globe"
            to="/extensions/green-score"
            isActive={location.pathname === '/extensions/green-score'}
          />

          <Tab
            title="Groups"
            icon="users"
            to="/extensions/green-score/groups"
            isActive={location.pathname === '/extensions/green-score/groups'}
          />

          <Tab title="Add New Group" fillBackground to="/extensions/green-score/groups/new" />
        </>
      )}
    </PageTitle>
  );
}
