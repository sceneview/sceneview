<!-- category: Fixed -->
The Roborazzi goldens under `samples/android-demo/src/test/snapshots/` are now a
declared input of the demo's test task, so `verifyRoborazziDebug` re-runs the
comparison whenever a golden changes instead of coming back `UP-TO-DATE` having
read nothing (#3029). CI drops the `--rerun` workaround, and `DEMO_TESTING.md`
stops teaching the bare command as if it were safe without saying why it now is.
Of the three surfaces named in #3034, only this one still exists: `impact-check.sh`
and the `automation-map` skill were removed with the agent harness in #3244.
