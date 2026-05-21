<!-- category: Tests -->
- QA: `web-perf-qa.sh` now enforces a tuned Lighthouse perf budget (mobile preset — FCP/LCP/CLS + perf-score) instead of always emitting an advisory verdict, and `device-qa.sh` records the result as an advisory `web-perf` leg so a budget breach surfaces in `device-qa-report.json`'s release gate (#1898, follow-up of #1879).
