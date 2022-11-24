import React from 'react';
import { Link, useHistory, useParams } from 'react-router-dom';
import { Table } from '../../components/inputs';
import { nextClient } from '../../services/BackOfficeServices';
import { useEntityFromURI } from '../../util';

export default ({ injectTopBar }) => {
  const params = useParams();
  const history = useHistory();
  const entity = useEntityFromURI();

  const domainColumn = {
    title: 'Domain',
    filterId: 'frontend.domains.0',
    cell: (item) => {
      return (
        <>
          {item.frontend.domains[0] || '-'}{' '}
          {item.frontend.domains.length > 1 && (
            <span className="badge bg-secondary">{item.frontend.domains.length - 1} more</span>
          )}
        </>
      );
    },
  };

  const targetColumn = {
    title: 'Domain',
    filterId: 'backend.targets.0.hostname',
    cell: (item) => {
      return (
        <>
          {item.backend.targets[0]?.hostname || '-'}{' '}
          {item.backend.targets.length > 1 && (
            <span className="badge bg-secondary">{item.backend.targets.length - 1} more</span>
          )}
        </>
      );
    },
  };

  const exposedColumn = {
    title: 'Enabled',
    id: 'enabled',
    style: { textAlign: 'center', width: 70 },
    notFilterable: true,
    cell: (_, item) =>
      item.enabled ? (
        <span className="fas fa-check-circle" style={{ color: '#5cb85c' }} />
      ) : (
        <span className="fas fa-times" style={{ color: '#D5443F' }} />
      ),
  };

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    entity.lowercase == 'route' ? domainColumn : undefined,
    entity.lowercase == 'route' ? targetColumn : undefined,
    exposedColumn,
  ].filter((c) => c);

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
        fetchItems={(paginationState) =>
          nextClient.findAllWithPagination(nextClient.ENTITIES[entity.fetchName], {
            ...paginationState,
            fields: ['name', 'enabled', 'frontend.domains.0', 'backend.targets.0.hostname', 'id'],
          })
        }
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
