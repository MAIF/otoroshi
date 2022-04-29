import React, { useEffect } from 'react';
import { Link, useHistory, useParams } from 'react-router-dom';
import { Table } from '../../components/inputs';
import { nextClient } from '../../services/BackOfficeServices';

export default ({}) => {
  const params = useParams();
  const history = useHistory();

  useEffect(() => {}, []);

  const columns = [{ title: 'Name', content: (item) => item.name }];

  return (
    <div className="designer">
      <Table
        parentProps={{ params }}
        navigateTo={(item) => history.push(`/unnamed/${item.id}?tab=flow`)}
        navigateOnEdit={(item) => history.push(`/unnamed/${item.id}?tab=informations`)}
        selfUrl="services"
        defaultTitle="Services"
        itemName="Route"
        formSchema={null}
        formFlow={null}
        columns={columns}
        fetchItems={() => nextClient.find(nextClient.ENTITIES.SERVICES)}
        deleteItem={(item) => nextClient.remove(nextClient.ENTITIES.SERVICES, item)}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        rawEditUrl={true}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link className="btn btn-primary" to={'v2/new?tab=informations'}>
              <i className="fas fa-plus-circle" /> Create new service
            </Link>
          </div>
        )}
      />
    </div>
  );
};
