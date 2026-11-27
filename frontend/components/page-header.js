export function mountPageHeader(main) {
  const heading = main?.querySelector('[data-page-heading]');
  if (!heading) return;

  // Keep page context in the content column. Moving it into the topbar makes flex spacing
  // depend on unrelated search/action controls and shifts the title away from the page body.
  heading.classList.add('app-page-heading', 'min-w-0');
  heading.querySelector('p:not(.editorial-eyebrow)')?.classList.remove('truncate');
  heading.querySelector('h1')?.classList.add('break-words');
}
