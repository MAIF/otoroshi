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

export function getRankAndLetterFromScore(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));

  return {
    score: isNaN(score) ? 0 : score,
    rank: rankIdx === -1 ? 'Not evaluated' : Object.keys(GREEN_SCORE_GRADES)[rankIdx],
    letter: String.fromCharCode(65 + rankIdx),
  };
}
