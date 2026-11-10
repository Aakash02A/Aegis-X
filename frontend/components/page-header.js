export function mountPageHeader(main, header) {
  const heading = main.querySelector('[data-page-heading]');
  if (!heading) return;
  heading.classList.remove('border-b', 'pb-6');
  heading.classList.add('app-page-heading', 'min-w-0');
  heading.querySelector('p:not(.editorial-eyebrow)')?.classList.add('truncate');
  heading.querySelector('h1')?.classList.add('break-words');
  const menu = header.querySelector('[data-workspace-menu]');
  header.insertBefore(heading, menu?.nextSibling || header.firstChild);
}
