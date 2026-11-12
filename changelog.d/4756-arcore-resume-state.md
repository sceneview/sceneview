<!-- category: Fixed -->
- **Android AR resume failures (#4756).** Failed resumes show a retryable error; successful retries clear placement and demo errors. Camera-busy retries preserve the session and its anchors, fatal errors recreate the session, and retries of a running session do nothing. Posted camera handover callback exceptions are logged without reporting a session failure.
