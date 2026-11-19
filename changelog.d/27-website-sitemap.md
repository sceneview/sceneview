<!-- category: Fixed -->
- **Website sitemap stays current.** The deploy now generates `sitemap.xml` with canonical URLs and each page's last git change as `<lastmod>`, and leaves out redirects (`/go/*`), the 404 page and pages marked `noindex`. CI runs the generator's offline tests.
