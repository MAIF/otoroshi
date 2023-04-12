export default {
  id: 'cp:otoroshi.next.plugins.NgHasAllowedUsersValidator',
  config_schema: {
    usernames: {
      type: 'array',
      array: true,
      format: null,
      label: 'usernames',
    },
    emails: {
      type: 'array',
      array: true,
      format: null,
      label: 'email addresses',
    },
    email_domains: {
      type: 'array',
      array: true,
      format: null,
      label: 'email domains',
    },
    metadata_match: {
      type: 'array',
      array: true,
      format: null,
      label: 'metadata math',
    },
    metadata_not_match: {
      type: 'array',
      array: true,
      format: null,
      label: 'metadata not match',
    },
    profile_match: {
      type: 'array',
      array: true,
      format: null,
      label: 'profile match',
    },
    profile_not_match: {
      type: 'array',
      array: true,
      format: null,
      label: 'profile not match',
    },
  },
  config_flow: [
    'usernames', 
    'emails', 
    'email_domains', 
    'metadata_match', 
    'metadata_not_match', 
    'profile_match', 
    'profile_not_match', 
  ],
};