function columnLabels(table) {
  return [...table.querySelectorAll('thead th')].map((cell) => cell.textContent.trim().replace(/\s+/g, ' '));
}

function labelRows(table) {
  const labels = columnLabels(table);
  for (const row of table.querySelectorAll('tbody tr')) {
    const cells = [...row.children].filter((cell) => cell.tagName === 'TD');
    if (cells.length === 1 && cells[0].hasAttribute('colspan')) continue;
    cells.forEach((cell, index) => cell.setAttribute('data-label', labels[index] || 'Details'));
  }
}

export function enhanceResponsiveTables(scope = document) {
  for (const table of scope.querySelectorAll('[data-responsive-table]')) {
    labelRows(table);
    const body = table.tBodies[0];
    if (!body || body.dataset.responsiveObserver === 'true') continue;
    body.dataset.responsiveObserver = 'true';
    new MutationObserver(() => labelRows(table)).observe(body, { childList: true, subtree: true });
  }
}
