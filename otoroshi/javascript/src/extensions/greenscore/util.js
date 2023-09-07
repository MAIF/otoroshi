const MAX_GREEN_SCORE_NOTE = 6000;

const GREEN_SCORE_GRADES = {
  '#2ecc71': (rank) => rank >= MAX_GREEN_SCORE_NOTE,
  '#27ae60': (rank) => rank < MAX_GREEN_SCORE_NOTE && rank >= 3000,
  '#f1c40f': (rank) => rank < 3000 && rank >= 2000,
  '#d35400': (rank) => rank < 2000 && rank >= 1000,
  '#c0392b': (rank) => rank < 1000,
};

export function caclculateRuleGroup(routeRules, ruleId) {
  return routeRules.sections
    .find(s => s.id === ruleId)
    .rules
    .reduce((acc, rule) => {
      return (acc += rule.enabled
        ? MAX_GREEN_SCORE_NOTE * (rule.section_weight / 100) * (rule.weight / 100)
        : 0);
    }, 0)
}

export function calculateGreenScore(routeRules) {
  const { sections } = routeRules;

  const score = sections.reduce((acc, item) => {
    return (
      acc +
      item.rules.reduce((acc, rule) => {
        return (acc += rule.enabled
          ? MAX_GREEN_SCORE_NOTE * (rule.section_weight / 100) * (rule.weight / 100)
          : 0);
      }, 0)
    );
  }, 0);

  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));

  return {
    score,
    rank: rankIdx === -1 ? 'Not evaluated' : Object.keys(GREEN_SCORE_GRADES)[rankIdx],
    letter: String.fromCharCode(65 + rankIdx),
  };
}

export function getLetter(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));
  return String.fromCharCode(65 + rankIdx);
}

export function getRank(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));
  return rankIdx === -1 ? 'Not evaluated' : Object.keys(GREEN_SCORE_GRADES)[rankIdx];
}

export function getRankAndLetterFromScore(score) {
  return {
    score: isNaN(score) ? 0 : score,
    rank: getRank(score),
    letter: getLetter(score)
  };
}

export function getThreshold(greenScoreId) {
  return fetch(`/bo/api/proxy/api/extensions/green-score/${greenScoreId}`, {
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  })
    .then((r) => r.json())
}

export function calculateThresholdsScore(greenScoreId, routes) {
  return getThreshold(greenScoreId)
    .then(score => {
      const globalScore = routes.reduce((acc, route) => {
        const { thresholds } = route.rulesConfig;

        if (score && score.scores.length > 0) {
          const dataOutIdx = Object.values(thresholds.dataOut).findIndex(
            (value) => score.scores[0].dataOutReservoir <= value
          );
          const headersOutIdx = Object.values(thresholds.headersOut).findIndex(
            (value) => score.scores[0].headersOutReservoir <= value
          );
          const pluginsIdx = Object.values(thresholds.plugins).findIndex(
            (value) => score.scores[0].pluginsReservoir <= value
          );

          const thresholdsScore = Math.round(
            ((dataOutIdx !== -1 ? dataOutIdx : Object.keys(thresholds.dataOut).length) +
              (headersOutIdx !== -1 ? headersOutIdx : Object.keys(thresholds.headersOut).length) +
              (pluginsIdx !== -1 ? pluginsIdx : Object.keys(thresholds.plugins).length)) /
            3
          );

          return acc + getRankAndLetterFromScore((3 - thresholdsScore) * 2000).score;
        } else {
          return acc + getRankAndLetterFromScore(0).score;
        }
      }, 0)

      return {
        letter: getLetter(globalScore),
        rank: getRank(globalScore)
      }
    });
}