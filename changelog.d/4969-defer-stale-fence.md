<!-- category: Fixed -->
- **Android teardown deferral (#4969).** The five-second diagnostic no longer authorizes destruction on a stale fence. Missing fences and refused polls also keep teardown pending. Native destruction remains synchronous and can still block; this mitigates the fallback regression without guaranteeing an ANR-free teardown.
