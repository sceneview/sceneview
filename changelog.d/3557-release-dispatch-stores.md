<!-- category: Fixed -->
Release-fast releases now reach the stores: `tag-release.sh` dispatches play-store.yml and app-store.yml alongside release.yml, and a read-only `prod-status.sh` probe reports what is actually live on every public surface versus `VERSION_NAME` (#3557).
