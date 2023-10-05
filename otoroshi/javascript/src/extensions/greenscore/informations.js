import React from 'react';

import GreenScoreConfigsPage from './page';

export default function GreenScoreExtension(extensionId, ctx) {
  return {
    id: extensionId,
    sidebarItems: [
      {
        title: 'Green scores',
        text: 'All your Green Scores',
        path: 'extensions/green-score',
        icon: 'leaf',
      },
    ],
    creationItems: [],
    dangerZoneParts: [],
    features: [
      {
        title: 'Green Score configs.',
        description: 'All your Green Score configs.',
        img: 'leaf', // TODO: change image
        link: '/extensions/green-score',
        display: () => true,
        icon: () => 'fa-leaf', // TODO: change icon
      },
    ],
    searchItems: [
      {
        action: () => {
          window.location.href = `/bo/dashboard/extensions/green-score`;
        },
        env: <span className="fas fa-leaf" />,
        label: 'Green Score Dashboard',
        value: '/',
      },
    ],
    routes: [
      // TODO: add more route here if needed
      {
        path: '/extensions/green-score/:taction/:titem',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />;
        },
      },
      {
        path: '/extensions/green-score/:taction',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />;
        },
      },
      {
        path: '/extensions/green-score',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />;
        },
      },
    ],
  };
}
