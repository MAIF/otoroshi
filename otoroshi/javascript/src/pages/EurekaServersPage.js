import React, { useEffect } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';

export function EurekaServersPage(props) {
  const { history, setTitle } = props;

  const columns = [
    {
      title: 'Name',
      content: (item) => item.name,
    },
  ];

  useEffect(() => {
    setTitle(`Eureka Servers`);
  });

  const gotoEurekaServer = (eurekaServer) => {
    window.location = `/bo/dashboard/eureka-servers/edit/${eurekaServer.id}`;
  };

  if (!window.__user.superAdmin) {
    return null;
  }

  return (
    <Table
      parentProps={props}
      selfUrl="eureka-servers"
      defaultTitle="Eureka servers"
      defaultValue={() => ({})}
      itemName="eureka-servers"
      columns={columns}
      fetchItems={BackOfficeServices.findAllEurekaServers}
      rowNavigation={true}
      extractKey={(item) => item.id}
      navigateTo={gotoEurekaServer}
      itemUrl={(i) => `/bo/dashboard/eureka-servers/edit/${i.id}`}
    />
  );
}
