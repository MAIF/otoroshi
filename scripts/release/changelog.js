const fs = require('fs-extra');
const fetch = require('node-fetch');
const moment = require('moment');
const argv = require('minimist')(process.argv.slice(2));

const GITHUB_TOKEN = process.env.GITHUB_TOKEN;

function generateChangelog(version, last_version, milestone, dir) {
  const query = `{
    repository(owner: \"MAIF\", name: \"otoroshi\") {
      milestone(number: ${milestone}) {
        id
        issues(first: 500) {
          edges {
            node {
              number,
              title,
              author {
                login
              },
              assignees(first: 5) {
                edges {
                  node {
                    login
                  }
                }
              },
              labels(first: 10) {
                edges {
                  node {
                    name
                  }
                }
              }
            }
          }
        }
      }
    }
  }`.replace(/\n/g, '\n');
  const body = {"query": query, "variables":null};
  fetch('https://api.github.com/graphql', {
    method: 'POST',
    headers: {
        'Authorization': `Bearer ${GITHUB_TOKEN}`,
        'accept': 'application/json',
        'content-type': 'application/graphql',
    },
    body: JSON.stringify(body),
  }).then(r => r.json()).then(r => {
    const contributors = new Set();
    const issues = r.data.repository.milestone.issues.edges.map(edge => {
      return {
        "number": edge.node.number,
        "title": edge.node.title,
        "users": [...new Set([...edge.node.assignees.edges.map(e => e.node.login), edge.node.author.login])],
        "author": edge.node.author.login,
        "assignees": edge.node.assignees.edges.map(e => e.node.login),
        "labels":  edge.node.labels.edges.map(e => e.node.name),
      }
    });
    issues.map(issue => issue.users.map(user => contributors.add(user)));

    const addedIssues = issues.filter(issue => {
      if (issue.labels.indexOf('documentation') > -1) return false;
      if (issue.labels.indexOf('change') > -1) return false;
      if (issue.labels.indexOf('enhancement') > -1) return false;
      if (issue.labels.indexOf('bug') > -1) return false;
      if (issue.labels.indexOf('fix') > -1) return false;
      if (issue.labels.indexOf('documentation') > -1) return false;
      if (issue.labels.indexOf('added') > -1) return true;
      return true;
    });
    const changedIssues = issues.filter(issue => issue.labels.indexOf('change') > -1 || issue.labels.indexOf('enhancement') > -1);
    const fixedIssues = issues.filter(issue => issue.labels.indexOf('bug') > -1 || issue.labels.indexOf('fix') > -1);
    const documentationIssues = issues.filter(issue => issue.labels.indexOf('documentation') > -1);

    let added = '';
    if (addedIssues.length > 0) {
      added = `### Added 

${[...addedIssues].map(c => `- ${c.title} (#${c.number})`).join('\n')}       
      `;
    }

    let changed = '';
    if (changedIssues.length > 0) {
      changed = `### Changed 

${[...changedIssues].map(c => `- ${c.title} (#${c.number})`).join('\n')}       
      `;
    }

    let fixed = '';
    if (fixedIssues.length > 0) {
      fixed = `### Fixed 

${[...fixedIssues].map(c => `- ${c.title} (#${c.number})`).join('\n')}       
      `;
    }

    let docs = '';
    if (documentationIssues.length > 0) {
      docs = `### Documentation 

${[...documentationIssues].map(c => `- ${c.title} (#${c.number})`).join('\n')}       
      `;
    }

    const template = `

https://github.com/MAIF/otoroshi/milestone/${milestone}?closed=1
https://github.com/MAIF/otoroshi/compare/v${last_version}...v${version}
https://github.com/MAIF/otoroshi/releases/tag/v${version}


${added}
${changed}
${fixed}
${docs}
### Contributors

${[...contributors].map(c => `* @${c}`).join('\n')}`;

    const githubReleaseTemplate = `# Otoroshi version ${version}
${template}`;

    const fileReleaseTemplate = `## [${version}] - ${moment().format('YYYY-MM-DD')}
${template}`;

    fs.writeFileSync(`${dir}/release-${version}/github-release.md`, githubReleaseTemplate);
    const oldChangelog = fs.readFileSync(`${dir}/CHANGELOG.md`).toString('utf-8');
    const newChangelog = oldChangelog.replace('---\n', `---\n\n${fileReleaseTemplate}\n`);
    fs.writeFileSync(`${dir}/CHANGELOG.md`, newChangelog);
    console.log(githubReleaseTemplate);
    console.log('')
    console.log(fileReleaseTemplate);

  })
}

const version = argv.version;
const last_version = argv.last;
const milestone = argv.milestone;
const dir = argv.dir;

// generateChangelog(version, last_version, milestone, dir);

generateChangelog('16.11.0', '16.10.3', '75', '/Users/mathieuancelin/projects/otoroshi')