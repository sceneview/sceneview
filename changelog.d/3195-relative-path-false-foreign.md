<!-- category: Fixed -->
The pre-push gate no longer reads a relative path as another clone's tree. Its
foreign-tree detector required a leading `/` without checking what preceded it,
so `samples/android-demo/src/main/.../GeneratedDemos.kt` — a line the gate writes
itself — yielded `/android-demo/src/…` and graded the repository's own clean run
`COULD NOT RUN`, refusing every push.
