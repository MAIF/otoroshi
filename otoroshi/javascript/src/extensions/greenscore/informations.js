import React from 'react';

import GreenScoreConfigsPage from './page';

export default function GreenScoreExtension(extensionId, ctx) {
  return {
    id: extensionId,
    sidebarItems: [
      {
        title: 'Green scores',
        text: 'All your Green Scores',
        path: 'extensions/green-score/green-score-configs',
        icon: 'leaf'
      }
    ],
    creationItems: [],
    dangerZoneParts: [],
    features: [
      {
        title: 'Green Score configs.',
        description: 'All your Green Score configs.',
        img: 'leaf', // TODO: change image
        link: '/extensions/green-score/green-score-configs',
        display: () => true,
        icon: () => 'fa-leaf', // TODO: change icon
      },
    ],
    searchItems: [
      {
        action: () => {
          window.location.href = `/bo/dashboard/green-score/green-score-configs`
        },
        env: <span className="fas fa-leaf" />,
        label: 'Green Score configs.',
        value: 'green-score-configs',
      }
    ],
    routes: [
      // TODO: add more route here if needed
      {
        path: '/extensions/green-score/green-score-configs/:taction/:titem',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      },
      {
        path: '/extensions/green-score/green-score-configs/:taction',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      },
      {
        path: '/extensions/green-score/green-score-configs',
        component: (props) => {
          return <GreenScoreConfigsPage {...props} />
        }
      }
    ],
  }
}
