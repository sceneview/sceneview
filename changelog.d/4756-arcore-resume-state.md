<!-- category: Fixed -->
- **Android AR resume failures (#4756).** A failed resume publishes the existing retryable session failure state; successful resume clears it. Retrying from a paused or detached host does nothing. Display-geometry and host-callback exceptions propagate even during a posted camera handover.
