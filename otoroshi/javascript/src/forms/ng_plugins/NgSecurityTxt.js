export default {
  id: 'cp:otoroshi.next.plugins.NgSecurityTxt',
  config_schema: {
    contact: { 
      type: 'string',
      label: 'Contact',
    },
    encryption: { 
      type: 'string',
      label: 'Encryption key',
    },
    acknowledgments: { 
      type: 'string',
      label: 'Aknowledgments',
    },
    preferred_languages: { 
      type: 'string',
      label: 'Preferred languages',
    },
    policy: { 
      type: 'string',
      label: 'Policy',
    },
    hiring: { 
      type: 'string',
      label: 'Hiring',
    },
  },
  config_flow: [
    'contact',
    'encryption',
    'acknowledgments',
    'preferred_languages',
    'policy',
    'hiring',
  ],
};