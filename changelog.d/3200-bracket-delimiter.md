<!-- category: Fixed -->
The pre-push gate now recognises a foreign source tree when the log announces
it in brackets — `[/Users/other/clone/src/main/A.kt]`. The delimiter class that
lets a path start after `:`, `=` or a quote had no `[`, so the pattern could not
begin there and a contaminated log graded clean. #3195 fixed the same function
from the false-red side; this is the false-green half, and the union of both.
