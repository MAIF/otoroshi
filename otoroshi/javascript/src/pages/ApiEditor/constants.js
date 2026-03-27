import { API_STATE } from './model';

export const MAX_WIDTH = 720;

export const LIFECYCLE_STEPS = [
  {
    key: API_STATE.STAGING,
    label: 'Staging',
    icon: 'fas fa-flask',
    color: 'hsla(184, 9%, 62%, 1)',
  },
  {
    key: API_STATE.PUBLISHED,
    label: 'Published',
    icon: 'fas fa-check-circle',
    color: 'hsla(150, 52%, 51%, 1)',
  },
  {
    key: API_STATE.DEPRECATED,
    label: 'Deprecated',
    icon: 'fas fa-exclamation-triangle',
    color: 'hsla(40, 94%, 58%, 1)',
  },
  {
    key: API_STATE.REMOVED,
    label: 'Closed',
    icon: 'fas fa-times-circle',
    color: 'hsla(0, 75%, 55%, 1)',
  },
];

export const STATE_PERMISSIONS = {
  [API_STATE.STAGING]: {
    allowed: [
      { icon: 'fas fa-pen', text: 'Edit endpoints, flows, backends and access modes' },
      { icon: 'fas fa-vial', text: 'Test the API with testing headers' },
      { icon: 'fas fa-key', text: 'Create and manage subscriptions' },
      { icon: 'fas fa-clone', text: 'Duplicate this API' },
    ],
    denied: [
      { icon: 'fas fa-globe', text: 'Receive production traffic' },
      { icon: 'fas fa-user-plus', text: 'Allow external access modes to subscribe' },
    ],
  },
  [API_STATE.PUBLISHED]: {
    allowed: [
      { icon: 'fas fa-globe', text: 'Receive production traffic' },
      { icon: 'fas fa-user-plus', text: 'Accept new subscriptions' },
      { icon: 'fas fa-pen', text: 'Edit configuration via draft mode' },
      { icon: 'fas fa-vial', text: 'Test the API with testing headers' },
      { icon: 'fas fa-clone', text: 'Duplicate this API' },
    ],
    denied: [],
  },
  [API_STATE.DEPRECATED]: {
    allowed: [
      { icon: 'fas fa-globe', text: 'Serve existing subscriptions and traffic' },
      { icon: 'fas fa-pen', text: 'Edit configuration via draft mode' },
      { icon: 'fas fa-clone', text: 'Duplicate this API' },
    ],
    denied: [{ icon: 'fas fa-user-plus', text: 'Accept new subscriptions' }],
  },
  [API_STATE.REMOVED]: {
    allowed: [
      { icon: 'fas fa-undo', text: 'Reopen and move back to staging' },
      { icon: 'fas fa-clone', text: 'Duplicate this API' },
    ],
    denied: [
      { icon: 'fas fa-globe', text: 'Receive any traffic' },
      { icon: 'fas fa-user-plus', text: 'Accept new subscriptions' },
      { icon: 'fas fa-pen', text: 'Edit endpoints, flows or backends' },
      { icon: 'fas fa-vial', text: 'Test the API' },
    ],
  },
};
