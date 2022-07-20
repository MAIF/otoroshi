import React from 'react';
import { Link, useHistory, useParams } from 'react-router-dom';
import { Table } from '../../components/inputs';
import { nextClient } from '../../services/BackOfficeServices';
import { useEntityFromURI } from '../../util';

export default ({ injectTopBar }) => {
  const params = useParams();
  const history = useHistory();
  const entity = useEntityFromURI();

  const columns = [
    {
      title: 'Name',
      content: (item) => item.name
    },
    {
      title: 'Domain → Target',
      cell: item => {
        return <>
          {(item.frontend.domains[0] || '-')} {item.frontend.domains.length > 1 && <span className='badge bg-secondary'>{item.frontend.domains.length-1} more</span>}  → {' '}
          {(item.backend.targets[0]?.hostname || '-')} {item.backend.targets.length > 1 && <span className='badge bg-secondary'>{item.backend.targets.length-1} more</span>}
        </>
      }
    },
    {
      title: 'Exposed',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      cell: (_, item) => (item.enabled ? <span className="fas fa-check-circle" style={{ color: "#f9b000" }} /> : null),
    }
  ];

  return (
    <div className="designer">
      <Table
        parentProps={{ params }}
        navigateTo={(item) => history.push(`/${entity.link}/${item.id}?tab=flow`)}
        navigateOnEdit={(item) => history.push(`/${entity.link}/${item.id}?tab=informations`)}
        selfUrl={entity.link}
        defaultTitle={entity.capitalizePlural}
        itemName={entity.capitalize}
        formSchema={null}
        formFlow={null}
        columns={columns}
        fetchItems={() => nextClient.find(nextClient.ENTITIES[entity.fetchName])}
        deleteItem={(item) => nextClient.remove(nextClient.ENTITIES[entity.fetchName], item)}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        rawEditUrl={true}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link className="btn btn-primary" to={`${entity.link}/new?tab=informations`}>
              <i className="fas fa-plus-circle" /> Create new {entity.lowercase}
            </Link>
            {injectTopBar}
          </div>
        )}
      />
    </div>
  );
};
