// @ts-check
import {themes as prismThemes} from 'prism-react-renderer';
import rehypeImgBaseUrl from './plugins/rehype-img-baseurl.js';
import remarkFileInclude from './plugins/remark-file-include.js';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Otoroshi',
  tagline: 'Lightweight, blazing fast API Gateway & Reverse Proxy',
  favicon: 'img/favicon.png',

  future: {
    v4: true,
  },

  url: 'https://maif.github.io',
  baseUrl: '/otoroshi/next/',

  organizationName: 'MAIF',
  projectName: 'otoroshi',

  onBrokenLinks: 'warn',

  markdown: {
    format: 'detect',
    hooks: {
      onBrokenMarkdownLinks: 'warn',
      onBrokenMarkdownImages: 'warn',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  themes: [
    [
      require.resolve("@easyops-cn/docusaurus-search-local"),
      /** @type {import("@easyops-cn/docusaurus-search-local").PluginOptions} */
      ({
        hashed: true,
        language: ["en"],
        highlightSearchTermsOnTargetPage: true,
        explicitSearchResultPath: true,
        indexBlog: true,
      }),
    ],
  ],

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/MAIF/otoroshi/tree/master/manual/next/',
          remarkPlugins: [remarkFileInclude],
          rehypePlugins: [
            [rehypeImgBaseUrl, {baseUrl: '/otoroshi/next/'}],
          ],
        },
        blog: {
          showReadingTime: true,
          editUrl: 'https://github.com/MAIF/otoroshi/tree/master/manual/next/',
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'img/otoroshi-social-card.png',
      colorMode: {
        defaultMode: 'light',
        disableSwitch: false,
        respectPrefersColorScheme: true,
      },
      announcementBar: {
        id: 'star_us',
        content: 'If you like Otoroshi, give it a <a target="_blank" rel="noopener noreferrer" href="https://github.com/MAIF/otoroshi">star on GitHub</a>!',
        backgroundColor: '#f9b000',
        textColor: '#1b1b1d',
        isCloseable: true,
      },
      navbar: {
        title: 'Otoroshi',
        logo: {
          alt: 'Otoroshi Logo',
          src: 'img/otoroshi-logo.png',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'docsSidebar',
            position: 'left',
            label: 'Documentation',
          },
          {
            to: '/api-reference',
            label: 'API Reference',
            position: 'left',
          },
          {
            to: '/blog',
            label: 'Blog',
            position: 'left',
          },
          {
            label: 'Ecosystem',
            position: 'left',
            items: [
              {
                label: 'Daikoku - Developer Portal',
                href: 'https://maif.github.io/daikoku/',
              },
              {
                label: 'Izanami - Feature Flags',
                href: 'https://maif.github.io/izanami/',
              },
              {
                label: 'Cloud APIM',
                href: 'https://www.cloud-apim.com/',
              },
              {
                label: 'MAIF OSS',
                href: 'https://maif.github.io/',
              },
            ],
          },
          {
            href: 'https://discord.gg/dmbwZrfpcQ',
            label: 'Discord',
            position: 'right',
          },
          {
            href: 'https://github.com/MAIF/otoroshi',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Documentation',
            items: [
              {
                label: 'Getting Started',
                to: '/docs/getting-started',
              },
              {
                label: 'Main Entities',
                to: '/docs/entities',
              },
              {
                label: 'Detailed Topics',
                to: '/docs/topics',
              },
              {
                label: 'How-to Guides',
                to: '/docs/how-to-s',
              },
            ],
          },
          {
            title: 'Community',
            items: [
              {
                label: 'Discord',
                href: 'https://discord.gg/dmbwZrfpcQ',
              },
              {
                label: 'GitHub Discussions',
                href: 'https://github.com/MAIF/otoroshi/discussions',
              },
              {
                label: 'Stack Overflow',
                href: 'https://stackoverflow.com/questions/tagged/otoroshi',
              },
            ],
          },
          {
            title: 'More',
            items: [
              {
                label: 'GitHub',
                href: 'https://github.com/MAIF/otoroshi',
              },
              {
                label: 'Releases',
                href: 'https://github.com/MAIF/otoroshi/releases',
              },
              {
                label: 'Roadmap',
                href: 'https://github.com/orgs/MAIF/projects/20/views/1',
              },
              {
                label: 'MAIF Open Source',
                href: 'https://maif.github.io/',
              },
            ],
          },
          {
            title: 'Ecosystem',
            items: [
              {
                label: 'Daikoku',
                href: 'https://maif.github.io/daikoku/',
              },
              {
                label: 'Izanami',
                href: 'https://maif.github.io/izanami/',
              },
              {
                label: 'Cloud APIM',
                href: 'https://www.cloud-apim.com/',
              },
            ],
          },
        ],
        logo: {
          alt: 'MAIF OSS Logo',
          src: 'img/otoroshi-logo.png',
          href: 'https://maif.github.io/',
          width: 50,
          height: 50,
        },
        copyright: `Copyright © ${new Date().getFullYear()} MAIF — Open Source. Built with Docusaurus.`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['java', 'bash', 'json', 'yaml', 'rust', 'scala', 'docker'],
      },
    }),
};

export default config;
