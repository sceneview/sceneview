<!-- category: Fixed -->
- **android-demo**: coming back from an AR demo, the launcher no longer decodes every card preview again on the main thread, and a card no longer lays out the captions of its whole row each time it is built — both are kept in memory and reused (#4461).
