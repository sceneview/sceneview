<!-- category: Fixed -->
- **On-demand resource cleanup without a surface.** Pending resource destroys now finish their grace period even when no swap chain is available, allowing idle render loops to park. Queue observation only writes snapshot state when the queue becomes empty or non-empty.
