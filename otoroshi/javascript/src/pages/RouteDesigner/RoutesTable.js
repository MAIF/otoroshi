import React, { useEffect, useRef, useState } from 'react';
import { Link, useHistory, useParams } from 'react-router-dom';
import { Table } from '../../components/inputs';
import { nextClient } from '../../services/BackOfficeServices';
import { useEntityFromURI } from '../../util';
import Loader from '../../components/Loader';

const FIELDS_SELECTOR = "otoroshi-fields-selector";

export function RoutesTable(props) {
  const params = useParams();
  const history = useHistory();
  const entity = useEntityFromURI();

  const [loading, setLoading] = useState(true)
  const [fields, setFields] = useState({
    'id': false,
    'name': true,
    'description': true,
    'tags': false,
    'metadata': false,
    'enabled': true,
    'groups': false,
    'frontend': true,
    'backend': true,
    'plugins': false,
    'created at': false,
    'metadata.updated_at': true,
    'metadata.created_at': false
  })

  const domainColumn = {
    title: 'Frontend',
    filterId: 'frontend.domains.0',
    cell: (item, a) => {
      return (
        <>
          {item.frontend.domains[0] || '-'}{' '}
          {item.frontend.domains.length > 1 && (
            <span
              className="badge bg-secondary"
              style={{ cursor: 'pointer' }}
              title={item.frontend.domains.map((v) => ` - ${v}`).join('\n')}
            >
              {item.frontend.domains.length - 1} more
            </span>
          )}
        </>
      );
    },
  };

  const targetColumn = {
    title: 'Backend',
    filterId: 'backend.targets.0.hostname',
    cell: (item) => {
      return (
        <>
          {item.backend.targets[0]?.hostname || '-'}{' '}
          {item.backend.targets.length > 1 && (
            <span
              className="badge bg-secondary"
              style={{ cursor: 'pointer' }}
              title={item.backend.targets
                .map((v) => ` - ${v.tls ? 'https' : 'http'}://${v.hostname}:${v.port}`)
                .join('\n')}
            >
              {item.backend.targets.length - 1} more
            </span>
          )}
        </>
      );
    },
  };

  const exposedColumn = {
    title: 'Enabled',
    id: 'enabled',
    style: { textAlign: 'center', width: 90 },
    notFilterable: true,
    cell: (_, item) =>
      item.enabled ? (
        <span className="fas fa-check-circle" style={{ color: 'var(--color-green)' }} />
      ) : (
        <span className="fas fa-times" style={{ color: 'var(--color-red)' }} />
      ),
  };

  const updatedAtColumn = {
    title: 'Updated',
    filterId: 'metadata.updated_at',
    id: 'metadata.updated_at',
    style: { textAlign: 'center', width: 160 },
    notFilterable: true,
    cell: (_, item) => {
      return <span>{item.metadata.updated_at ? `${new Date(item.metadata.updated_at).toLocaleDateString()} ${new Date(item.metadata.updated_at).toLocaleTimeString()}` : '-'}</span>
    }
  }

  const createdAtColumn = {
    title: 'Created',
    filterId: 'metadata.created_at',
    id: 'metadata.created_at',
    style: { textAlign: 'center', width: 160 },
    notFilterable: true,
    cell: (_, item) => {
      return <span>{item.metadata.created_at ? `${new Date(item.metadata.created_at).toLocaleDateString()} ${new Date(item.metadata.created_at).toLocaleTimeString()}` : '-'}</span>
    }
  }

  const idColumn = {
    title: 'Id',
    content: item => item.id
  }

  const descriptionColumn = {
    title: 'Description',
    content: item => item.description
  }

  const tagsColumn = {
    title: 'Tags',
    content: item => (item.tags || []).join(','),
    notSortable: true,
    notFilterable: true
  }

  const metadataColumn = {
    title: 'Metadata',
    content: item => Object.entries(item.metadata || {}).map(([key, value]) => `${key}:${value}`).join(' - '),
    notSortable: true,
    notFilterable: true
  }

  const groupsColumn = {
    title: 'Groups',
    content: item => (item.groups || []).join(","),
    notSortable: true,
    notFilterable: true
  }

  const pluginsColumn = {
    title: 'Plugins',
    content: item => item.plugins?.length || 0,
    notSortable: true,
    notFilterable: true
  }

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
      wrappedCell: (v, item, table) => {
        if (props.globalEnv && props.globalEnv.adminApiId === item.id) {
          return (
            <span
              title="This route is the API that drives the UI you're currently using. Without it, Otoroshi UI won't be able to work and anything that uses Otoroshi admin API too. You might not want to delete it"
              className="badge bg-danger"
            >
              {item.name}
            </span>
          );
        }
        return item.name;
      },
    },
    idColumn,
    descriptionColumn,
    tagsColumn,
    metadataColumn,
    groupsColumn,
    pluginsColumn,
    entity.lowercase == 'route' ? domainColumn : undefined,
    entity.lowercase == 'route' ? targetColumn : undefined,
    exposedColumn,
    updatedAtColumn,
    createdAtColumn
  ]
    .filter((c) => c && (fields[c.title.toLowerCase()] || fields[c.filterId?.toLowerCase()]))

  const deleteItem = (item, table) => {
    if (props.globalEnv.adminApiId === item.id) {
      return window
        .newConfirm(
          `The route you're trying to delete is the Otoroshi Admin API that drives the UI you're currently using. Without it, Otoroshi UI won't be able to work and anything that uses Otoroshi admin API too. Do you really want to do that ?`
        )
        .then((ok1) => {
          if (ok1) {
            window.newConfirm(`Are you sure you really want to do that ?`).then((ok2) => {
              if (ok1 && ok2) {
                nextClient.remove(nextClient.ENTITIES[entity.fetchName], item).then(() => {
                  // table.update();
                });
              }
            });
          }
        });
    } else {
      return nextClient.remove(nextClient.ENTITIES[entity.fetchName], item).then(() => {
        // table.update();
      });
    }
  };

  const fetchItems = (paginationState) =>
    nextClient.forEntityNext(nextClient.ENTITIES[entity.fetchName])
      .findAllWithPagination({
        ...paginationState,
        fields: ['name', 'enabled', 'frontend.domains', 'backend.targets', 'id', 'metadata',
          fields['id'] ? 'id' : undefined,
          fields['description'] ? 'description' : undefined,
          fields['tags'] ? 'tags' : undefined,
          fields['metadata'] ? 'metadata' : undefined,
          fields['groups'] ? 'groups' : undefined,
          fields['plugins'] ? 'plugins' : undefined
        ].filter(c => c),
      })

  const ref = useRef()

  const onFieldsChange = fields => {
    if (ref.current) {
      ref.current.update()
    }

    saveFields(fields)
  }

  useEffect(() => {
    loadFields()
  }, [])

  const loadFields = () => {
    try {
      const values = JSON.parse(localStorage.getItem(FIELDS_SELECTOR || '{}'));

      console.log(values.routes)
      if (values.routes)
        setFields(values.routes)

    } catch (e) {
      console.log(e);
    } finally {
      setLoading(false)
    }
  };

  const saveFields = fields => {
    try {
      const values = JSON.parse(localStorage.getItem(FIELDS_SELECTOR) || '{}');

      console.log('save fields', fields)

      localStorage.setItem(
        FIELDS_SELECTOR,
        JSON.stringify({
          ...values,
          routes: fields,
        })
      );
    } catch (e) {
      console.log(e);
    }
  };

  return <Loader loading={loading}>
    <div className="designer">
      <Table
        ref={ref}
        parentProps={{ params }}
        navigateTo={(item) => history.push(`/${entity.link}/${item.id}?tab=flow`)}
        navigateOnEdit={(item) => history.push(`/${entity.link}/${item.id}?tab=informations`)}
        selfUrl={entity.link}
        defaultTitle={entity.capitalizePlural}
        itemName={entity.capitalize}
        formSchema={null}
        formFlow={null}
        columns={columns}
        fields={fields}
        onToggleField={(column, enabled) => {
          const newFields = {
            ...fields,
            [column]: enabled
          }
          onFieldsChange(newFields)
          setFields(newFields)
        }}
        deleteItem={(item) => deleteItem(item)}
        defaultSort="metadata.updated_at"
        defaultSortDesc="true"
        fetchItems={fetchItems}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        rawEditUrl={true}
        displayTrash={(item) => item.id === props.globalEnv.adminApiId}
        injectTopBar={() => (
          <div className="btn-group input-group-btn">
            <Link className="btn btn-primary btn-sm" to={`${entity.link}/new?tab=informations`}>
              <i className="fas fa-plus-circle" /> Create new {entity.lowercase}
            </Link>
            {props.injectTopBar}
          </div>
        )}
      />
    </div>
  </Loader>
}
