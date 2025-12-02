import React, { Component, useEffect, useRef, useState } from 'react';
import { Table, Form } from '../components/inputs';

import { nextClient } from '../services/BackOfficeServices';
import { useParams, useHistory, Link } from 'react-router-dom';
import Loader from '../components/Loader';
import { QueryClientProvider, useQuery } from 'react-query';
import { queryClient } from './TesterPage';
import { Row } from '../components/Row';
import Designer from './RouteDesigner/Designer';

export function RouteTemplatesPage(props) {
  return <QueryClientProvider client={queryClient}>
    <RouteTemplatesTable {...props} />
  </QueryClientProvider>
}

const FIELDS_SELECTOR = 'otoroshi-fields-selector';

const CORE_FIELDS = [
  'id',
  'name',
  'description',
  'tags',
  'metadata'
];

export function RouteTemplatesTable(props) {
  const params = useParams();
  const history = useHistory();

  useEffect(() => {
    props.setTitle('Route templates')

    return () => props.setTitle(undefined)
  }, [])

  const [loading, setLoading] = useState(true);
  const [fields, setFields] = useState({
    name: true,
    description: true,
    id: false,
    tags: false,
    metadata: false
  });

  const idColumn = {
    title: 'Id',
    content: (item) => item.id,
  };

  const descriptionColumn = {
    title: 'Description',
    content: (item) => item.description,
  };

  const tagsColumn = {
    title: 'Tags',
    content: (item) => (item.tags || []).join(','),
    notSortable: true,
  };

  const metadataColumn = {
    title: 'Metadata',
    content: (item) =>
      Object.entries(item.metadata || {})
        .map(([key, value]) => `${key}:${value}`)
        .join(' - '),
    notSortable: true,
  };

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    idColumn,
    descriptionColumn,
    tagsColumn,
    metadataColumn
  ].filter((c) => c && (fields[c.title?.toLowerCase()] || fields[c.filterId?.toLowerCase()]));

  const deleteItem = (item, table) => {
    return nextClient.remove(nextClient.ENTITIES.ROUTE_TEMPLATES, item).then(() => {
      // table.update();
    });
  };

  const fetchItems = (paginationState) => {
    return nextClient.forEntityNext(nextClient.ENTITIES.ROUTE_TEMPLATES).findAllWithPagination({
      ...paginationState,
      fields: [
        'id',
        ...Object.keys(fields).map((field) => (fields[field] ? field : undefined)),
      ].filter((c) => c),
    });
  };

  const fetchTemplate = () => nextClient.forEntityNext(nextClient.ENTITIES.ROUTE_TEMPLATES).template();

  const ref = useRef();

  const onFieldsChange = (fields) => {
    if (ref.current) {
      ref.current.update();
    }

    saveFields(fields);
  };

  useEffect(() => {
    loadFields();
  }, []);

  const defaultRouteTemplate = useQuery('getRouteTemplate', nextClient
    .forEntityNext(nextClient.ENTITIES.ROUTE_TEMPLATES)
    .template)

  const loadFields = () => {
    try {
      const values = JSON.parse(localStorage.getItem(FIELDS_SELECTOR || '{}'));

      if (values.routes) setFields(values.routes);
    } catch (e) {
    } finally {
      setLoading(false);
    }
  };

  const saveFields = (fields) => {
    try {
      const values = JSON.parse(localStorage.getItem(FIELDS_SELECTOR) || '{}');

      localStorage.setItem(
        FIELDS_SELECTOR,
        JSON.stringify({
          ...values,
          routes: fields,
        })
      );
    } catch (e) {
      // console.log(e);
    }
  };

  const schema = {
    id: {
      type: 'string',
      label: 'Id',
      props: {
        disabled: true
      }
    },
    name: {
      type: 'string',
      props: {
        label: 'Name',
      }
    },
    description: {
      type: 'string',
      props: {
        label: 'Description',
      }
    },
    metadata: {
      type: 'object',
      label: 'Metadata'
    },
    tags: {
      type: 'array',
      label: 'Tags'
    },
    route: {
      renderer: (props) => {
        return <div className="designer">
          <Designer
            history={history}
            value={props.value}
            setValue={props.onChange}
            setSaveButton={() => { }}
          />
        </div>
      }
    }
  }

  const flow = [
    {
      type: 'group',
      name: 'Information',
      fields: ['id', 'name', 'description', 'metadata', 'tags'],
    },
    {
      type: 'group',
      name: 'Plugins',
      fields: ['route']
    }
  ]

  return (
    <Loader loading={loading || defaultRouteTemplate.isLoading}>
      <div className="designer">
        <Table
          ref={ref}
          parentProps={props}
          navigateTo={(item) => {
            console.log(item)
            history.push(`/route-templates/edit/${item.id}`)
          }}
          navigateOnEdit={(item) => history.push(`/route-templates/edit/${item.id}`)}
          selfUrl="route-templates"
          defaultTitle="Route Templates"
          defaultValue={() => defaultRouteTemplate.data}
          itemName="Route template"
          formSchema={schema}
          formFlow={flow}
          columns={columns}
          fields={fields}
          coreFields={CORE_FIELDS}
          addField={(fieldPath) => {
            const newFields = {
              ...fields,
              [fieldPath]: true,
            };
            setFields(newFields);
            onFieldsChange(newFields);
          }}
          removeField={(fieldPath) => {
            const { [fieldPath]: _, ...newFields } = fields;

            setFields(newFields);
            onFieldsChange(newFields);
          }}
          onToggleField={(column, enabled) => {
            const newFields = {
              ...fields,
              [column]: enabled,
            };
            onFieldsChange(newFields);
            setFields(newFields);
          }}
          createItem={nextClient
            .forEntityNext(nextClient.ENTITIES.ROUTE_TEMPLATES)
            .create}
          updateItem={nextClient
            .forEntityNext(nextClient.ENTITIES.ROUTE_TEMPLATES)
            .update}
          deleteItem={(item) => deleteItem(item)}
          defaultSort="name"
          defaultSortDesc="true"
          fetchItems={fetchItems}
          fetchTemplate={fetchTemplate}
          stayAfterSave={true}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          export={true}
          kubernetesKind="proxy.otoroshi.io/RouteTemplate"
          extractKey={(item) => item.id}
        />
      </div>
    </Loader>
  );
}

