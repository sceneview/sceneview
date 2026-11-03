<!-- category: Fixed -->
- **Demo home hero no longer crash-loops on GPUs that cannot compile its reflections.** On some Motorola, Honor, Xreal and Vivo devices, Filament's screen-space-reflection blur shader fails to compile and the app aborted on every launch. The hero now marks each quality rung on trial until it has drawn, and never starts a rung that killed the app again (#4926).
