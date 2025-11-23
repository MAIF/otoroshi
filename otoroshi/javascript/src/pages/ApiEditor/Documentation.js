import {useParams} from "react-router-dom";
import React, {useEffect, useState} from "react";
import SimpleLoader from "./SimpleLoader";
import PageTitle from "../../components/PageTitle";
import {FeedbackButton} from "../RouteDesigner/FeedbackButton";
import MonacoEditor from "@monaco-editor/react";
import { Form } from "../../components/inputs/Form"

import { useDraftOfAPI, VersionBadge } from "./index";

function ApiDocumentationResource(props) {
  const flow = [
    'path',
    'title',
    'description',
    'content_type',
    'text_content',
    'css_icon_class',
    'json_content',
    'base64_content',
    'site_page',
    'transform',
    'transform_wrapper',
    'url',
    'http_headers',
    'http_timeout',
    'http_follow_redirects',
  ];
  const schema = {
    path: { type: 'string', props: { label: 'Path'}},
    title: { type: 'string', props: { label: 'Title'}},
    description: { type: 'string', props: { label: 'Description'}},
    content_type: { type: 'string', props: { label: 'Content-Type'}},
    text_content: { type: 'text', props: { label: 'Text Content'}},
    css_icon_class: { type: 'string', props: { label: 'CSS Icon class'}},
    json_content: { type: 'text', props: { label: 'Json Content'}},
    base64_content: { type: 'text', props: { label: 'Base64 Content'}},
    site_page: { type: 'bool', props: { label: 'Site page'}},
    transform: { type: 'string', props: { label: 'Transform'}},
    transform_wrapper: { type: 'string', props: { label: 'Transform wrapper'}},
    url: { type: 'string', props: { label: 'URL'}},
    http_headers: { type: 'object', props: { label: 'Http Headers'}},
    http_timeout: { type: 'number', props: { label: 'Http timeout', suffix: 'milliseconds' }},
    http_follow_redirects: { type: 'bool', props: { label: 'Http follow redirects'}},
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={props.itemValue ? (v => {
          props.value[props.idx] = v;
          props.onChange(props.value);
        }) : (props.onChange)}
      />
    </div>
  )
}

function ApiDocumentationPlan(props) {
  const flow = [
    'id',
    'name',
    'description',
    'throttling_quota',
    'daily_quota',
    'monthly_quota',
    'tags',
    'metadata',
  ];
  const schema = {
    id: {
      type: 'string',
      props: { label: 'ID' },
    },
    name: {
      type: 'string',
      props: { label: 'Name' },
    },
    description: {
      type: 'string',
      props: { label: 'Description' },
    },
    throttling_quota: {
      type: 'number',
      props: { label: 'Throttling Quota', suffix: 'per window' },
    },
    daily_quota: {
      type: 'number',
      props: { label: 'Daily Quota', suffix: 'calls/day' },
    },
    monthly_quota: {
      type: 'number',
      props: { label: 'Monthly Quota', suffix: 'calls/month' },
    },
    tags: {
      type: 'array',
      props: { label: 'tags' }
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' }
    },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={props.itemValue ? (v => {
          props.value[props.idx] = v;
          props.onChange(props.value);
        }) : (props.onChange)}
      />
    </div>
  )
}

function ApiDocumentationResourceRef(props) {
  const flow = [
    'title',
    'description',
    'link',
    'icon',
  ];
  const schema = {
    title: {
      type: 'string',
      props: { label: 'Title' },
    },
    description: {
      type: 'string',
      props: { label: 'Description' },
    },
    link: {
      type: 'string',
      props: { label: 'Link' },
    },
    icon: {
      type: ApiDocumentationResource,
      props: { label: 'Icon' },
    },
  };
  return (
    <div className="col-sm-10">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={props.itemValue ? (v => {
          props.value[props.idx] = v;
          props.onChange(props.value);
        }) : (props.onChange)}
      />
    </div>
  )
}

function ApiDocumentationRedirection(props) {
  const flow = [
    'from',
    'to',
  ];
  const schema = {
    from: {
      type: 'string',
      props: { label: 'From' },
    },
    to: {
      type: 'string',
      props: { label: 'To' },
    },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={props.itemValue ? (v => {
          props.value[props.idx] = v;
          props.onChange(props.value);
        }) : (props.onChange)}
      />
    </div>
  )
}

function ApiDocumentationSidebarItem(props) {
  const flow = [
    'kind',
    'icon',
    'label',
    'link',
    'links'
  ];
  const schema = {
    kind: {
      type: 'select',
      props: { label: 'Kind', possibleValues: [{ label: 'Category', value: 'category' }, { label: 'Link', value: 'link' }] },
    },
    link: { type: 'string', props: { label: 'Link' } },
    links: { type: 'array', props: { label: 'Links', component: ApiDocumentationSidebarItem } },
    label: { type: 'string', props: { label: 'Label' } },
    icon: { type: ApiDocumentationResource, props: { label: 'Icon' } },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={props.itemValue ? (v => {
          props.value[props.idx] = v;
          props.onChange(props.value);
        }) : (props.onChange)}
      />
    </div>
  )
}

function ApiDocumentationSidebar(props) {
  const flow = [
    'label',
    'path',
    'items',
    'icon',
  ];
  const schema = {
    label: { type: 'string', props: { label: 'Label' } },
    path: { type: 'string', props: { label: 'Path' } },
    items: { type: 'array', props: { label: 'Items', component: ApiDocumentationSidebarItem } },
    icon: { type: ApiDocumentationResource, props: { label: 'Icon' } },
  };
  return (
    <div className="col-sm-12">
      <Form
        flow={flow}
        schema={schema}
        value={props.itemValue || props.value}
        onChange={props.itemValue ? (v => {
          props.value[props.idx] = v;
          props.onChange(props.value);
        }) : (props.onChange)}
      />
    </div>
  )
}

export function Documentation(props) {

  const params = useParams();
  const [showJson, setShowJson] = useState(false);
  const { item, updateItem } = useDraftOfAPI();
  const [code, setCode] = useState('');
  const [newItem, setNewItem] = useState(null);

  useEffect(() => {
    if (item && code === '') {
      setCode(JSON.stringify(item.documentation || {}, null, 2));
      setNewItem(item.documentation);
    }
  }, [item]);

  const updateDoc = () => {
    return updateItem({ ...item, documentation: newItem });
  }

  const flow = [
    'enabled',
    'metadata',
    'tags',
    '>>>Remote',
    'source.url',
    'source.headers',
    'source.timeout',
    'source.follow_redirects',
    '>>>Home',
    'home',
    '>>>Logo',
    'logo',
    '>>>Navigation',
    'navigation',
    '>>>Footer',
    'footer',
    '>>>Banner',
    'banner',
    '>>>Plans',
    'plans',
    '>>>Redirections',
    'redirections',
    '>>>References',
    'references',
    '>>>Search',
    'search.enabled',
    '>>>Resources',
    'resources',
  ];
  const schema = {
    'enabled': {
      'type': 'bool',
      props: { 'label': 'Enabled' }
    },
    'metadata': {
      'type': 'object',
      props: { 'label': 'Metadata' }
    },
    'tags': {
      'type': 'array',
      props: { 'label': 'Tags' }
    },
    'source.url': {
      'type': 'string',
      props: { 'label': 'URL' }
    },
    'source.headers': {
      'type': 'object',
      props: { 'label': 'Headers' }
    },
    'source.follow_redirects': {
      'type': 'bool',
      props: { 'label': 'Follow Redirects' }
    },
    'source.timeout': {
      'type': 'number',
      props: { 'label': 'Timeout', 'suffix': 'milliseconds' },
    },
    'home': {
      'type': ApiDocumentationResource,
      props: { 'label': 'Home' }
    },
    'logo': {
      'type': ApiDocumentationResource,
      props: { 'label': 'Logo' }
    },
    'references': {
      'type': 'array',
      props: { component: ApiDocumentationResourceRef }
    },
    'resources': {
      'type': 'array',
      props: { component: ApiDocumentationResource }
    },
    'navigation': {
      'type': 'array',
      props: { 'label': 'Navigation', component: ApiDocumentationSidebar }
    },
    'redirections': {
      'type': 'array',
      props: { component: ApiDocumentationRedirection }
    },
    'footer': {
      'type': ApiDocumentationResource,
      props: { 'label': 'Footer' }
    },
    'search.enabled': {
      'type': 'bool',
      props: { 'label': 'Search' }
    },
    'banner': {
      'type': ApiDocumentationResource,
      props: { 'label': 'Banner' }
    },
    'plans': {
      'type': 'array',
      props: { component: ApiDocumentationPlan }
    },
  };

  if (!item) return <SimpleLoader />;
  return (
    <>
      <PageTitle title="Documentation" {...props}>
        <div className="btn-group" style={{ marginRight: 10 }}>
          <button type="button" className={`btn btn-primary ${showJson ? '' : 'active'}`} onClick={e => setShowJson(!showJson)}>Form</button>
          <button type="button" className={`btn btn-primary ${showJson ? 'active' : ''}`} onClick={e => setShowJson(!showJson)}>Json editor</button>
        </div>
        <FeedbackButton
          type="success"
          className="d-flex ms-auto"
          onPress={updateDoc}
          text={
            <div className="d-flex align-items-center">
              Update <VersionBadge size="xs" />
            </div>
          }
        />
      </PageTitle>
      {showJson && <MonacoEditor
        height={window.innerHeight - 140}
        width="100%"
        theme="vs-dark"
        defaultLanguage="json"
        value={code}
        options={{
          automaticLayout: true,
          selectOnLineNumbers: true,
          minimap: { enabled: true },
          lineNumbers: true,
          glyphMargin: false,
          folding: true,
          lineDecorationsWidth: 0,
          lineNumbersMinChars: 0,
        }}
        onChange={(newValue) => {
          try {
            setNewItem(JSON.parse(newValue));
          } catch (e) {
          }
        }}
      />}
      {!showJson && (
        <Form
          flow={flow}
          schema={schema}
          value={newItem || {}}
          onChange={e => setNewItem(e)}
        />
      )}
    </>
  )
}